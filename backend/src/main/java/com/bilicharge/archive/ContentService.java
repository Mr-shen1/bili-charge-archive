package com.bilicharge.archive;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ContentService {
    private final ContentMapper mapper;

    ContentService(ContentMapper mapper) {
        this.mapper = mapper;
    }

    record Up(String uid, String displayName, String avatarUrl, boolean enabled) {}
    record Image(int position, String status, String url) {}
    record Dynamic(String dynamicId, String upUid, String upName, String upAvatarUrl,
                   boolean upEnabled, String title, String text, String publishedAt,
                   long storedCommentCount, boolean historical, boolean sourceUnavailable,
                   List<Image> images) {}
    record Comment(String dynamicId, String rpid, String rootRpid, String parentRpid,
                   String authorMid, String authorName, String authorAvatarUrl, int authorLevel,
                   String text, String publishedAt, long likeCount, long replyCount,
                   long storedReplyCount, boolean isUp, boolean sourceUnavailable, List<Image> images) {}
    private record Cursor(String scope, boolean previous, LocalDateTime at, String id, int rank) {}

    @Transactional(readOnly = true)
    List<Up> ups() {
        return mapper.ups().stream().map(row -> new Up(row.uid, row.displayName,
                row.avatarUrl, row.enabled)).toList();
    }

    @Transactional(readOnly = true)
    PageEnvelope<Dynamic> dynamics(String upUid, String rawCursor) {
        if (upUid != null) {
            numeric(upUid);
        }
        String scope = "d:" + (upUid == null ? "all" : upUid);
        Cursor cursor = decode(rawCursor, scope);
        boolean previous = cursor != null && cursor.previous;
        List<ContentRows.Dynamic> found = mapper.dynamics(upUid, cursor == null ? null : cursor.at,
                cursor == null ? null : cursor.id, cursor == null ? null : cursor.rank,
                previous ? ">" : "<", previous ? "ASC" : "DESC",
                previous ? "<" : ">", previous ? "DESC" : "ASC");
        return page(found, cursor, scope, row -> row.publishedAt, row -> row.dynamicId,
                this::dynamic, row -> row.historyRank);
    }

    @Transactional(readOnly = true)
    Dynamic dynamic(String id) {
        numeric(id);
        ContentRows.Dynamic row = mapper.dynamic(id);
        if (row == null) throw notFound();
        return dynamic(row);
    }

    @Transactional(readOnly = true)
    PageEnvelope<Comment> comments(String dynamicId, String rootRpid, String rawCursor) {
        numeric(dynamicId);
        if (mapper.dynamic(dynamicId) == null) throw notFound();
        if (rootRpid != null) {
            numeric(rootRpid);
            if (mapper.rootExists(dynamicId, rootRpid) == null) throw notFound();
        }
        boolean ascending = rootRpid != null;
        String scope = "c:" + dynamicId + ":" + (rootRpid == null ? "root" : rootRpid);
        Cursor cursor = decode(rawCursor, scope);
        boolean previous = cursor != null && cursor.previous;
        String operator = ascending != previous ? ">" : "<";
        String order = ascending != previous ? "ASC" : "DESC";
        List<ContentRows.Comment> found = mapper.comments(dynamicId, rootRpid,
                cursor == null ? null : cursor.at, cursor == null ? null : cursor.id,
                operator, order);
        return page(found, cursor, scope, row -> row.publishedAt, row -> row.rpid,
                this::comment, null);
    }

    private Dynamic dynamic(ContentRows.Dynamic row) {
        List<Image> images = mapper.dynamicImages(row.dynamicId).stream().map(image ->
                new Image(image.position, image.status,
                        "/api/media/dynamics/" + row.dynamicId + "/" + image.position)).toList();
        return new Dynamic(row.dynamicId, row.upUid, row.upName, row.upAvatarUrl,
                row.upEnabled, row.title, row.text, utc(row.publishedAt),
                row.storedCommentCount, row.historyRank == 1, row.sourceUnavailable, images);
    }

    private Comment comment(ContentRows.Comment row) {
        List<Image> images = mapper.commentImages(row.dynamicId, row.rpid).stream().map(image ->
                new Image(image.position, image.status,
                        "/api/media/comments/" + row.dynamicId + "/" + row.rpid + "/" + image.position)).toList();
        return new Comment(row.dynamicId, row.rpid, row.rootRpid, row.parentRpid,
                row.authorMid, row.authorName, row.authorAvatarUrl, row.authorLevel,
                row.text, utc(row.publishedAt), row.likeCount, row.replyCount,
                row.storedReplyCount, row.isUp, row.sourceUnavailable, images);
    }

    private <R, T> PageEnvelope<T> page(List<R> found, Cursor cursor, String scope,
            Function<R, LocalDateTime> time, Function<R, String> id,
            Function<R, T> view, Function<R, Integer> rank) {
        boolean extra = found.size() > PageEnvelope.PAGE_SIZE;
        List<R> slice = new ArrayList<>(found.subList(0, Math.min(found.size(), PageEnvelope.PAGE_SIZE)));
        boolean previous = cursor != null && cursor.previous;
        if (previous) java.util.Collections.reverse(slice);
        boolean hasPrev = previous ? extra : cursor != null;
        boolean hasNext = previous ? cursor != null : extra;
        String next = hasNext && !slice.isEmpty()
                ? encode(scope, false, time.apply(slice.getLast()), id.apply(slice.getLast()),
                         rank == null ? null : rank.apply(slice.getLast())) : null;
        String prev = hasPrev && !slice.isEmpty()
                ? encode(scope, true, time.apply(slice.getFirst()), id.apply(slice.getFirst()),
                         rank == null ? null : rank.apply(slice.getFirst())) : null;
        return new PageEnvelope<>(slice.stream().map(view).toList(),
                new PageEnvelope.Page(PageEnvelope.PAGE_SIZE, next, prev, hasNext, hasPrev));
    }

    private String encode(String scope, boolean previous, LocalDateTime at, String id, Integer rank) {
        String plain = rank == null
                ? String.join("|", "v1", scope, previous ? "p" : "n", at.toString(), id)
                : String.join("|", "v2", scope, previous ? "p" : "n", rank.toString(), at.toString(), id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String raw, String scope) {
        if (raw == null) return null;
        if (raw.length() > 300 || !raw.matches("[A-Za-z0-9_-]+")) throw invalidCursor();
        try {
            String plain = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            boolean dynamic = scope.startsWith("d:");
            if (dynamic ? parts.length != 6 || !parts[0].equals("v2")
                        : parts.length != 5 || !parts[0].equals("v1")) throw invalidCursor();
            if (!parts[1].equals(scope)
                    || !(parts[2].equals("p") || parts[2].equals("n"))) throw invalidCursor();
            int shift = dynamic ? 1 : 0;
            int rank = 0;
            if (dynamic) {
                if (!(parts[3].equals("0") || parts[3].equals("1"))) throw invalidCursor();
                rank = Integer.parseInt(parts[3]);
            }
            if (!parts[4 + shift].matches("[1-9][0-9]{0,31}")) throw invalidCursor();
            return new Cursor(scope, parts[2].equals("p"), LocalDateTime.parse(parts[3 + shift]),
                    parts[4 + shift], rank);
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw invalidCursor();
        }
    }

    private String numeric(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,31}"))
            throw new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ID", "ID 必须是数字字符串");
        return value;
    }

    private String utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC).toString();
    }

    private AdminException invalidCursor() {
        return new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURSOR", "分页 cursor 无效");
    }

    private AdminException notFound() {
        return new AdminException(HttpStatus.NOT_FOUND, "NOT_FOUND", "内容不存在");
    }
}
