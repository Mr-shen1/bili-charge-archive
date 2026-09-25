package com.bilicharge.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BatchService {
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,31}");
    private final BatchMapper mapper;
    private final AdminMapper admin;
    private final ObjectMapper json;

    BatchService(BatchMapper mapper, AdminMapper admin, ObjectMapper json) {
        this.mapper = mapper;
        this.admin = admin;
        this.json = json;
    }

    @Transactional
    BatchResult submit(String uid, BatchRequest request) {
        id(uid, "UP UID");
        if (request == null) throw invalid("批次不能为空");
        List<BatchRequest.Item> items = list(request.items());
        List<String> completed = list(request.baselineCompletedDynamicIds());
        List<BatchRequest.AvailabilityChange> availability = list(request.availabilityChanges());
        if (items.isEmpty() && completed.isEmpty() && availability.isEmpty()) throw invalid("批次不能为空");
        int commentCount = items.stream().mapToInt(item -> item == null ? 0 : list(item.comments()).size()).sum();
        if (commentCount > 100) throw invalid("单批评论不能超过 100 条");

        // Locking the UP serializes overlapping batches for one worker and keeps disable/config changes ordered.
        Integer enabled = mapper.lockUp(uid);
        if (enabled == null) throw new AdminException(HttpStatus.NOT_FOUND, "NOT_FOUND", "UP 不存在");
        if (enabled == 0) throw new AdminException(HttpStatus.CONFLICT, "UP_DISABLED", "UP 已停用");
        AdminRows.Up up = admin.up(uid);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Set<String> seenDynamics = new HashSet<>();
        Set<String> seenComments = new HashSet<>();
        int newDynamics = 0;
        int newComments = 0;
        int newEvents = 0;
        int releasedEvents = 0;

        for (BatchRequest.Item item : items) {
            if (item == null || item.dynamic() == null || item.scanState() == null) {
                throw invalid("每项都需要动态和扫描状态");
            }
            BatchRequest.Dynamic dynamic = item.dynamic();
            String dynamicId = id(dynamic.dynamicId(), "动态 ID");
            if (!uid.equals(dynamic.upUid()) || !seenDynamics.add(dynamicId)) {
                throw invalid("动态归属或批次内动态 ID 无效");
            }
            Map<String, BatchRequest.EventCandidate> candidates = candidates(dynamicId, item);
            String owner = mapper.dynamicOwner(dynamicId);
            if (owner != null && !uid.equals(owner)) {
                throw new AdminException(HttpStatus.CONFLICT, "DYNAMIC_OWNER_CONFLICT", "动态已归属其他 UP");
            }
            BatchRows.Dynamic row = dynamicRow(dynamic, now);
            boolean firstDynamic = owner == null;
            if (firstDynamic) {
                mapper.insertDynamic(row);
                newDynamics++;
            } else {
                mapper.updateDynamic(row);
            }
            syncDynamicImages(dynamicId, dynamic.images());
            BatchRequest.ScanState state = item.scanState();
            if (state.state() == null || !state.state().isObject()) throw invalid("扫描状态必须是 JSON 对象");
            mapper.upsertScanState(new BatchRows.ScanState(dynamicId, state.state().toString(),
                    time(state.lastCompleteScanAt()), time(state.lastFullScanAt()),
                    time(state.fullScanRetryAt())));
            boolean baselineComplete = mapper.baselineCompletedAt(dynamicId) != null;
            AdminRows.Route route = admin.route(dynamicId);
            if (route != null && !uid.equals(route.upUid)) throw invalid("专属路由与 UP 不匹配");
            Long allGroup = route == null ? up.defaultAllGroupId : route.allGroupId;
            Long upGroup = route == null ? up.defaultUpGroupId : route.upGroupId;
            List<PendingEvent> pending = new ArrayList<>();

            if (firstDynamic) {
                BatchRequest.EventCandidate candidate = required(candidates, "dynamic:" + dynamicId);
                pending.add(new PendingEvent(null, false, candidate, dynamic.images()));
            }
            for (BatchRequest.Comment comment : list(item.comments())) {
                if (comment == null) throw invalid("评论不能为空");
                String rpid = id(comment.rpid(), "评论 ID");
                if (!seenComments.add(dynamicId + ":" + rpid)) throw invalid("批次内评论 ID 重复");
                boolean firstComment = mapper.commentExists(dynamicId, rpid) == 0;
                boolean isUp = uid.equals(comment.authorMid());
                BatchRows.Comment commentRow = commentRow(dynamicId, comment, isUp, now);
                if (firstComment) {
                    mapper.insertComment(commentRow);
                    newComments++;
                } else {
                    mapper.updateComment(commentRow);
                }
                syncCommentImages(dynamicId, rpid, comment.images());
                if (firstComment && (isUp ? allGroup != null || upGroup != null : allGroup != null)) {
                    BatchRequest.EventCandidate candidate = required(candidates,
                            "comment:" + dynamicId + ":" + rpid);
                    pending.add(new PendingEvent(rpid, isUp, candidate, comment.images()));
                }
            }
            for (PendingEvent pendingEvent : pending) {
                newEvents += event(uid, dynamicId, pendingEvent.rpid(), pendingEvent.upComment(),
                        pendingEvent.candidate(), pendingEvent.fallbackImages(), baselineComplete ? now : null);
            }
        }

        Set<String> completionIds = new HashSet<>();
        for (String raw : completed) {
            String dynamicId = id(raw, "基线动态 ID");
            if (!completionIds.add(dynamicId)) throw invalid("基线完成 ID 重复");
            if (!uid.equals(mapper.dynamicOwner(dynamicId)) || mapper.scanStateExists(dynamicId) == 0) {
                throw invalid("基线动态不存在或扫描状态尚未保存");
            }
            mapper.completeBaseline(dynamicId, now);
            releasedEvents += mapper.releaseEvents(dynamicId, now);
        }

        Set<String> changed = new HashSet<>();
        for (BatchRequest.AvailabilityChange change : availability) {
            if (change == null || change.unavailable() == null) throw invalid("来源状态无效");
            String dynamicId = id(change.dynamicId(), "来源动态 ID");
            if (!uid.equals(mapper.dynamicOwner(dynamicId))) throw invalid("来源动态不属于该 UP");
            String rpid = change.rpid() == null ? null : id(change.rpid(), "来源评论 ID");
            String key = dynamicId + ":" + (rpid == null ? "" : rpid);
            if (!changed.add(key) || (rpid == null ? seenDynamics.contains(dynamicId) : seenComments.contains(key))) {
                throw invalid("同一批次的来源状态重复或与内容快照冲突");
            }
            if (rpid == null) {
                if (change.unavailable()) mapper.markDynamicUnavailable(dynamicId, now);
                else mapper.markDynamicAvailable(dynamicId);
            } else {
                if (mapper.commentExists(dynamicId, rpid) == 0) throw invalid("来源评论不存在");
                if (change.unavailable()) mapper.markCommentUnavailable(dynamicId, rpid, now);
                else mapper.markCommentAvailable(dynamicId, rpid);
            }
        }
        return new BatchResult(newDynamics, newComments, newEvents, releasedEvents);
    }

    private Map<String, BatchRequest.EventCandidate> candidates(String dynamicId, BatchRequest.Item item) {
        Map<String, BatchRequest.EventCandidate> result = new HashMap<>();
        Set<String> commentIds = new HashSet<>();
        for (BatchRequest.Comment comment : list(item.comments())) {
            if (comment == null) throw invalid("评论不能为空");
            commentIds.add(id(comment.rpid(), "评论 ID"));
        }
        for (BatchRequest.EventCandidate candidate : list(item.eventCandidates())) {
            if (candidate == null || candidate.text() == null) throw invalid("通知候选内容无效");
            String expected = switch (candidate.type() == null ? "" : candidate.type()) {
                case "DYNAMIC" -> {
                    if (candidate.commentRpid() != null) throw invalid("动态事件不能引用评论");
                    yield "dynamic:" + dynamicId;
                }
                case "COMMENT" -> {
                    String rpid = id(candidate.commentRpid(), "候选评论 ID");
                    if (!commentIds.contains(rpid)) throw invalid("候选评论不在本批内容中");
                    yield "comment:" + dynamicId + ":" + rpid;
                }
                default -> throw invalid("批次只接受动态与评论通知候选");
            };
            if (!expected.equals(candidate.dedupeKey()) || result.putIfAbsent(expected, candidate) != null) {
                throw invalid("通知候选去重键无效或重复");
            }
            imageUrls(candidate.imageSourceUrls());
        }
        return result;
    }

    private BatchRequest.EventCandidate required(Map<String, BatchRequest.EventCandidate> candidates, String key) {
        BatchRequest.EventCandidate candidate = candidates.get(key);
        if (candidate == null) throw invalid("新内容缺少通知候选：" + key);
        return candidate;
    }

    private int event(String uid, String dynamicId, String rpid, boolean isUp,
                      BatchRequest.EventCandidate candidate, List<String> fallbackImages,
                      LocalDateTime readyAt) {
        List<String> urls = candidate.imageSourceUrls() == null
                ? imageUrls(fallbackImages) : imageUrls(candidate.imageSourceUrls());
        String imageJson = json.valueToTree(urls).toString();
        return mapper.insertEvent(new BatchRows.Event(candidate.dedupeKey(), uid, dynamicId, rpid,
                candidate.type(), isUp, candidate.text(), imageJson, readyAt)) == 1 ? 1 : 0;
    }

    private BatchRows.Dynamic dynamicRow(BatchRequest.Dynamic source, LocalDateTime now) {
        id(source.commentOid(), "评论目标 OID");
        if (source.commentType() == null || source.commentType() < 1 || source.commentType() > 65535
                || source.text() == null || (source.title() != null && source.title().length() > 255)) {
            throw invalid("动态内容或评论目标无效");
        }
        return new BatchRows.Dynamic(source.dynamicId(), source.upUid(), source.title(), source.text(),
                requiredTime(source.publishedAt()), source.commentOid(), source.commentType(), now);
    }

    private BatchRows.Comment commentRow(String dynamicId, BatchRequest.Comment source,
                                         boolean isUp, LocalDateTime now) {
        id(source.authorMid(), "评论作者 UID");
        if ((source.rootRpid() == null) != (source.parentRpid() == null)) {
            throw invalid("楼中楼必须同时指定根评论和父评论");
        }
        if (source.rootRpid() != null) {
            id(source.rootRpid(), "根评论 ID");
            id(source.parentRpid(), "父评论 ID");
        }
        if (source.authorName() == null || source.authorName().isBlank() || source.authorName().length() > 100
                || source.authorAvatarUrl() != null && source.authorAvatarUrl().length() > 2048
                || source.text() == null || source.authorLevel() == null || source.authorLevel() < 0
                || source.authorLevel() > 65535 || source.likeCount() == null || source.likeCount() < 0
                || source.likeCount() > 4294967295L || source.replyCount() == null
                || source.replyCount() < 0 || source.replyCount() > 4294967295L) {
            throw invalid("评论内容或计数无效");
        }
        return new BatchRows.Comment(dynamicId, source.rpid(), source.rootRpid(), source.parentRpid(),
                source.authorMid(), source.authorName(), source.authorAvatarUrl(), source.authorLevel(),
                source.text(), requiredTime(source.publishedAt()), source.likeCount(), source.replyCount(),
                isUp, now);
    }

    private void syncDynamicImages(String dynamicId, List<String> images) {
        if (images == null) return;
        List<String> urls = imageUrls(images);
        for (int position = 0; position < urls.size(); position++) {
            String previous = mapper.dynamicImageSource(dynamicId, position);
            if (previous == null) mapper.insertDynamicImage(dynamicId, position, urls.get(position));
            else if (!previous.equals(urls.get(position))) mapper.resetDynamicImage(dynamicId, position, urls.get(position));
        }
        mapper.trimDynamicImages(dynamicId, urls.size());
    }

    private void syncCommentImages(String dynamicId, String rpid, List<String> images) {
        if (images == null) return;
        List<String> urls = imageUrls(images);
        for (int position = 0; position < urls.size(); position++) {
            String previous = mapper.commentImageSource(dynamicId, rpid, position);
            if (previous == null) mapper.insertCommentImage(dynamicId, rpid, position, urls.get(position));
            else if (!previous.equals(urls.get(position))) mapper.resetCommentImage(dynamicId, rpid, position, urls.get(position));
        }
        mapper.trimCommentImages(dynamicId, rpid, urls.size());
    }

    private List<String> imageUrls(List<String> raw) {
        if (raw == null) return List.of();
        if (raw.size() > 65535) throw invalid("图片数量过多");
        List<String> urls = new ArrayList<>(raw.size());
        for (String url : raw) {
            if (url == null || url.isBlank() || url.length() > 2048) throw invalid("图片来源地址无效");
            urls.add(url);
        }
        return urls;
    }

    private LocalDateTime requiredTime(String raw) {
        LocalDateTime parsed = time(raw);
        if (parsed == null) throw invalid("发布时间不能为空");
        return parsed;
    }

    private LocalDateTime time(String raw) {
        if (raw == null) return null;
        try {
            LocalDateTime result = LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC);
            if (result.getYear() < 1000 || result.getYear() > 9999) throw invalid("时间超出数据库范围");
            return result;
        } catch (DateTimeParseException error) {
            throw invalid("时间必须是 UTC ISO 8601 格式");
        }
    }

    private String id(String raw, String label) {
        if (raw == null || !ID.matcher(raw).matches()) throw invalid(label + " 格式无效");
        return raw;
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static AdminException invalid(String message) {
        return new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BATCH", message);
    }

    private record PendingEvent(String rpid, boolean upComment, BatchRequest.EventCandidate candidate,
                                List<String> fallbackImages) {}

    record BatchResult(int newDynamics, int newComments, int newEvents, int releasedEvents) {}
}
