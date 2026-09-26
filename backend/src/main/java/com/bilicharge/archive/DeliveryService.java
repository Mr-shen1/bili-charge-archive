package com.bilicharge.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeliveryService {
    private static final int LEASE_SECONDS = 600;
    private static final int[] RETRY_SECONDS = {5, 10, 15, 30, 60};
    private final DeliveryMapper mapper;
    private final AdminMapper admin;
    private final WebhookCipher cipher;
    private final ObjectMapper json;

    DeliveryService(DeliveryMapper mapper, AdminMapper admin, WebhookCipher cipher, ObjectMapper json) {
        this.mapper = mapper;
        this.admin = admin;
        this.cipher = cipher;
        this.json = json;
    }

    record Claim(long eventId, String role, String leaseToken, String leaseUntil,
                 String upUid, String dynamicId, String commentRpid,
                 String eventType, String messageText,
                 List<String> imageSourceUrls, String webhook) {}
    record Result(String workerId, String leaseToken, boolean success, String error) {}
    record Summary(long eventId, String role, String upUid, String eventType,
                   String status, Long groupId, String groupName, int attempts,
                   String nextRetryAt, String sentAt, String lastError) {}
    private record Cursor(long eventId, String role, boolean previous) {}

    @Transactional
    Claim claim(String requestedUp, String workerId) {
        if (workerId == null || !workerId.matches("[A-Za-z0-9_-]{1,24}")) throw invalid("workerId 无效");
        if (requestedUp != null && !requestedUp.matches("[1-9][0-9]{0,31}")) throw invalid("UP UID 无效");
        mapper.lockClaims();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Set<Long> blockedGroups = new HashSet<>();
        Map<String, AdminRows.Up> ups = new HashMap<>();
        for (DeliveryRows.Event event : mapper.readyEvents()) {
            AdminRows.Up up = ups.get(event.upUid);
            if (up == null) {
                // Route writes and batches lock this same UP row, so a claim sees one committed scope.
                admin.lockUp(event.upUid);
                up = admin.up(event.upUid);
                ups.put(event.upUid, up);
            }
            List<DeliveryRows.Target> targets = refresh(event, up, now);
            if (completeIfSettled(event, targets, now)) continue;
            for (DeliveryRows.Target target : targets) {
                if (target.groupId == null || target.status.equals("SENT") || target.status.equals("SKIPPED")) continue;
                long groupId = target.groupId;
                if (blockedGroups.contains(groupId)) continue;
                boolean due = target.status.equals("PENDING")
                        ? target.nextRetryAt == null || !target.nextRetryAt.isAfter(now)
                        : target.status.equals("SENDING") && target.leaseUntil != null
                          && !target.leaseUntil.isAfter(now);
                if (due && (requestedUp == null || requestedUp.equals(event.upUid))) {
                    String token = workerId + ":" + UUID.randomUUID();
                    LocalDateTime until = now.plusSeconds(LEASE_SECONDS);
                    if (mapper.claim(event.id, target.role, groupId, token, until, now) == 1) {
                        AdminRows.Group group = admin.group(groupId);
                        return new Claim(event.id, target.role, token, until.toString() + "Z",
                                event.upUid, event.dynamicId, event.commentRpid,
                                event.eventType, event.messageText, images(event.imageSourceUrls),
                                cipher.decrypt(group.webhookCiphertext));
                    }
                }
                blockedGroups.add(groupId);
            }
        }
        return null;
    }

    @Transactional
    void result(long eventId, String role, Result result) {
        if (result == null || result.workerId == null || result.leaseToken == null
                || !result.leaseToken.startsWith(result.workerId + ":")
                || !result.workerId.matches("[A-Za-z0-9_-]{1,24}")) throw invalid("租约信息无效");
        mapper.lockClaims();
        DeliveryRows.Event event = mapper.event(eventId);
        if (event == null) throw new AdminException(HttpStatus.NOT_FOUND, "NOT_FOUND", "通知事件不存在");
        admin.lockUp(event.upUid);
        DeliveryRows.Target target = mapper.target(eventId, role);
        if (target == null || !target.status.equals("SENDING") || !result.leaseToken.equals(target.leaseOwner)) {
            throw conflict("LEASE_EXPIRED", "通知租约已失效");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (result.success) {
            if (mapper.sent(eventId, role, result.leaseToken, now) != 1) throw conflict("LEASE_EXPIRED", "通知租约已失效");
        } else {
            int delay = RETRY_SECONDS[Math.min(Math.max(target.attempts, 1) - 1, RETRY_SECONDS.length - 1)];
            String reason = result.error != null && result.error.matches("[A-Z0-9_:-]{1,120}")
                    ? result.error : "SEND_FAILED";
            if (mapper.failed(eventId, role, result.leaseToken, now, now.plusSeconds(delay), reason) != 1) {
                throw conflict("LEASE_EXPIRED", "通知租约已失效");
            }
        }
        if (result.success) completeIfSettled(event, refresh(event, admin.up(event.upUid), now), now);
    }

    @Transactional(readOnly = true)
    PageEnvelope<Summary> recent(String uid, String status, String rawCursor) {
        if (uid != null && !uid.matches("[1-9][0-9]{0,31}")) throw invalid("UP UID 无效");
        if (status != null && !List.of("PENDING", "SENDING", "SENT", "SKIPPED").contains(status)) {
            throw invalid("投递状态无效");
        }
        String scope = (uid == null ? "all" : uid) + ":" + (status == null ? "all" : status);
        Cursor cursor = decode(rawCursor, scope);
        boolean previous = cursor != null && cursor.previous;
        List<Map<String, Object>> found = mapper.recent(uid, status,
                LocalDateTime.now(ZoneOffset.UTC).minusDays(90),
                cursor == null ? null : cursor.eventId,
                cursor == null ? null : cursor.role,
                previous ? ">" : "<", previous ? "ASC" : "DESC");
        boolean extra = found.size() > PageEnvelope.PAGE_SIZE;
        List<Map<String, Object>> slice = new ArrayList<>(found.subList(0,
                Math.min(found.size(), PageEnvelope.PAGE_SIZE)));
        if (previous) Collections.reverse(slice);
        boolean hasPrev = previous ? extra : cursor != null;
        boolean hasNext = previous ? cursor != null : extra;
        String next = hasNext && !slice.isEmpty() ? encode(scope, slice.getLast(), false) : null;
        String prev = hasPrev && !slice.isEmpty() ? encode(scope, slice.getFirst(), true) : null;
        List<Summary> data = slice.stream()
                .map(row -> new Summary(((Number) row.get("event_id")).longValue(),
                        (String) row.get("role"), (String) row.get("up_uid"),
                        (String) row.get("event_type"), (String) row.get("status"),
                        row.get("group_id") == null ? null : ((Number) row.get("group_id")).longValue(),
                        (String) row.get("group_name_snapshot"), ((Number) row.get("attempts")).intValue(),
                        utc((LocalDateTime) row.get("next_retry_at")), utc((LocalDateTime) row.get("sent_at")),
                        (String) row.get("last_error"))).toList();
        return new PageEnvelope<>(data,
                new PageEnvelope.Page(PageEnvelope.PAGE_SIZE, next, prev, hasNext, hasPrev));
    }

    @Transactional
    int cleanup() {
        return mapper.deleteCompletedBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(90));
    }

    private List<DeliveryRows.Target> refresh(DeliveryRows.Event event, AdminRows.Up up, LocalDateTime now) {
        AdminRows.Route route = event.dynamicId == null ? null : admin.route(event.dynamicId);
        Long all = route == null ? up.defaultAllGroupId : route.allGroupId;
        Long own = route == null ? up.defaultUpGroupId : route.upGroupId;
        List<String> roles = switch (event.eventType) {
            case "DYNAMIC" -> List.of("ALL", "UP");
            case "COMMENT" -> event.isUpComment ? List.of("ALL", "UP") : List.of("ALL");
            case "OPS_HEARTBEAT", "OPS_ERROR" -> List.of("OPS");
            default -> throw new IllegalStateException("未知通知类型");
        };
        List<DeliveryRows.Target> targets = new ArrayList<>();
        for (String role : roles) {
            Long groupId = switch (role) { case "ALL" -> all; case "UP" -> own; default -> up.opsGroupId; };
            DeliveryRows.Target target = mapper.target(event.id, role);
            if (groupId == null) {
                if (target != null && !target.status.equals("SENT") && !target.status.equals("SKIPPED")
                        && (!target.status.equals("SENDING") || target.leaseUntil == null
                            || !target.leaseUntil.isAfter(now))) {
                    mapper.skip(event.id, role);
                    target = mapper.target(event.id, role);
                }
            } else if (target == null) {
                AdminRows.Group group = admin.group(groupId);
                mapper.insertTarget(event.id, role, groupId, group.name);
                target = mapper.target(event.id, role);
            } else if (!target.status.equals("SENT")
                    && (target.status.equals("SKIPPED") || !groupId.equals(target.groupId))
                    && (target.status.equals("SKIPPED") || !target.status.equals("SENDING")
                        || target.leaseUntil == null || !target.leaseUntil.isAfter(now))) {
                AdminRows.Group group = admin.group(groupId);
                mapper.retarget(event.id, role, groupId, group.name);
                target = mapper.target(event.id, role);
            }
            if (target != null) targets.add(target);
        }
        return targets;
    }

    private boolean completeIfSettled(DeliveryRows.Event event, List<DeliveryRows.Target> targets,
                                      LocalDateTime now) {
        if (targets.stream().allMatch(t -> t.status.equals("SENT") || t.status.equals("SKIPPED"))) {
            mapper.complete(event.id, now);
            return true;
        }
        return false;
    }

    private List<String> images(String raw) {
        if (raw == null) return List.of();
        try {
            JsonNode value = json.readTree(raw);
            if (!value.isArray()) return List.of();
            List<String> urls = new ArrayList<>();
            value.forEach(node -> urls.add(node.asText()));
            return urls;
        } catch (Exception error) {
            throw new IllegalStateException("通知图片快照无效", error);
        }
    }

    private String utc(LocalDateTime value) { return value == null ? null : value.toString() + "Z"; }
    private String encode(String scope, Map<String, Object> row, boolean previous) {
        String plain = "v1|" + scope + "|" + (previous ? "p" : "n") + "|"
                + row.get("event_id") + "|" + row.get("role");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String raw, String scope) {
        if (raw == null) return null;
        if (raw.length() > 300 || !raw.matches("[A-Za-z0-9_-]+")) throw invalid("分页 cursor 无效");
        try {
            String plain = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 5 || !parts[0].equals("v1") || !parts[1].equals(scope)
                    || !(parts[2].equals("p") || parts[2].equals("n"))
                    || !parts[3].matches("[1-9][0-9]*")
                    || !List.of("ALL", "UP", "OPS").contains(parts[4])) throw invalid("分页 cursor 无效");
            return new Cursor(Long.parseLong(parts[3]), parts[4], parts[2].equals("p"));
        } catch (IllegalArgumentException error) {
            throw invalid("分页 cursor 无效");
        }
    }
    private AdminException invalid(String message) {
        return new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DELIVERY", message);
    }
    private AdminException conflict(String code, String message) {
        return new AdminException(HttpStatus.CONFLICT, code, message);
    }
}
