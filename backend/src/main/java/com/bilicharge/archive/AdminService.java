package com.bilicharge.archive;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminService {
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,31}");
    private static final Set<String> WEBHOOK_HOSTS = Set.of("open.feishu.cn", "open.larksuite.com");
    private final AdminMapper mapper;
    private final WebhookCipher cipher;
    private final BiliPreviewClient bili;

    AdminService(AdminMapper mapper, WebhookCipher cipher, BiliPreviewClient bili) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.bili = bili;
    }

    List<GroupView> groups() {
        return mapper.groups().stream().map(this::groupView).toList();
    }

    GroupView createGroup(String name, String webhook) {
        AdminRows.Group group = new AdminRows.Group();
        group.name = name(name);
        group.webhookCiphertext = cipher.encrypt(webhook(webhook));
        try {
            mapper.insertGroup(group);
        } catch (DuplicateKeyException error) {
            throw conflict("GROUP_NAME_EXISTS", "群名称已存在");
        }
        return groupView(group);
    }

    GroupView updateGroup(long id, String name, String webhook) {
        AdminRows.Group group = requireGroup(id);
        if (name != null) group.name = name(name);
        if (webhook != null) group.webhookCiphertext = cipher.encrypt(webhook(webhook));
        try {
            mapper.updateGroup(group);
        } catch (DuplicateKeyException error) {
            throw conflict("GROUP_NAME_EXISTS", "群名称已存在");
        }
        return groupView(group);
    }

    @Transactional
    void deleteGroup(long id) {
        requireGroup(id);
        List<String> references = mapper.groupReferences(id);
        if (!references.isEmpty()) {
            throw conflict("GROUP_IN_USE", "群仍被以下配置引用：" + String.join("、", references));
        }
        try {
            mapper.deleteGroup(id);
        } catch (DataIntegrityViolationException error) {
            // The foreign key closes the race between checking references and deleting the group.
            throw conflict("GROUP_IN_USE", "群仍被配置引用");
        }
    }

    BiliPreviewClient.User previewUp(String input) {
        return bili.previewUser(parseId(input, true));
    }

    List<UpView> ups() {
        return mapper.ups().stream().map(this::upView).toList();
    }

    UpView createUp(String uidInput, Long opsGroupId, Long allGroupId, Long upGroupId) {
        String uid = parseId(uidInput, true);
        validateGroups(opsGroupId, allGroupId, upGroupId, true);
        if (mapper.up(uid) != null) throw conflict("UP_EXISTS", "该 UP 已配置");
        BiliPreviewClient.User source = bili.previewUser(uid);
        AdminRows.Up up = new AdminRows.Up();
        up.uid = uid;
        up.displayName = source.displayName();
        up.avatarUrl = source.avatarUrl();
        up.enabled = true;
        up.opsGroupId = opsGroupId;
        up.defaultAllGroupId = allGroupId;
        up.defaultUpGroupId = upGroupId;
        try {
            mapper.insertUp(up);
        } catch (DataIntegrityViolationException error) {
            throw conflict("UP_EXISTS", "该 UP 已配置或群已变更");
        }
        return upView(up);
    }

    UpView updateUp(String uid, JsonNode body) {
        AdminRows.Up up = requireUp(uid);
        if (body == null || !body.isObject()) throw invalid("INVALID_UP", "更新内容必须是 JSON 对象");
        if (body.has("enabled")) {
            if (!body.get("enabled").isBoolean()) throw invalid("INVALID_UP", "启停状态必须是布尔值");
            up.enabled = body.get("enabled").asBoolean();
        }
        up.opsGroupId = patchedGroup(body, "opsGroupId", up.opsGroupId);
        up.defaultAllGroupId = patchedGroup(body, "defaultAllGroupId", up.defaultAllGroupId);
        up.defaultUpGroupId = patchedGroup(body, "defaultUpGroupId", up.defaultUpGroupId);
        validateGroups(up.opsGroupId, up.defaultAllGroupId, up.defaultUpGroupId, true);
        try {
            mapper.updateUp(up);
        } catch (DataIntegrityViolationException error) {
            throw invalid("INVALID_GROUP", "群配置已变更，请刷新后重试");
        }
        return upView(up);
    }

    List<RouteView> routes(String uid) {
        requireUp(uid);
        return mapper.routes(uid).stream().map(this::routeView).toList();
    }

    BiliPreviewClient.Dynamic previewRoute(String uid, String input) {
        requireUp(uid);
        return bili.previewDynamic(uid, parseId(input, false));
    }

    RouteView saveRoute(String uid, String dynamicIdInput, Long allGroupId, Long upGroupId) {
        requireUp(uid);
        String dynamicId = parseId(dynamicIdInput, false);
        validateGroups(null, allGroupId, upGroupId, false);
        AdminRows.Route existing = mapper.route(dynamicId);
        if (existing != null && !uid.equals(existing.upUid)) {
            throw conflict("ROUTE_OWNED_BY_OTHER_UP", "动态已由其他 UP 配置");
        }
        // Recheck the source when saving, so a forged or stale preview cannot create a fixed target.
        bili.previewDynamic(uid, dynamicId);
        AdminRows.Route route = new AdminRows.Route();
        route.dynamicId = dynamicId;
        route.upUid = uid;
        route.allGroupId = allGroupId;
        route.upGroupId = upGroupId;
        try {
            if (existing == null) mapper.insertRoute(route);
            else mapper.updateRoute(route);
        } catch (DataIntegrityViolationException error) {
            throw conflict("ROUTE_CONFLICT", "专属路由已变更，请刷新后重试");
        }
        return routeView(route);
    }

    void deleteRoute(String uid, String dynamicIdInput) {
        requireUp(uid);
        String dynamicId = parseId(dynamicIdInput, false);
        if (mapper.deleteRoute(uid, dynamicId) == 0) throw notFound("专属路由不存在");
    }

    UpStatus status(String uid) {
        AdminRows.Up up = requireUp(uid);
        return new UpStatus(uid, up.enabled, utc(up.workerHeartbeatAt), utc(up.lastScanStartedAt),
                utc(up.lastScanSucceededAt), utc(up.lastScanErrorAt), up.lastScanError,
                mapper.pendingCount(uid), mapper.failedCount(uid));
    }

    private GroupView groupView(AdminRows.Group group) {
        List<String> refs = mapper.groupReferences(group.id);
        return new GroupView(group.id, group.name, true, !refs.isEmpty(), refs);
    }

    private UpView upView(AdminRows.Up up) {
        return new UpView(up.uid, up.displayName, up.avatarUrl, up.enabled,
                up.opsGroupId, up.defaultAllGroupId, up.defaultUpGroupId,
                utc(up.lastScanSucceededAt), up.lastScanError,
                mapper.pendingCount(up.uid), mapper.failedCount(up.uid));
    }

    private RouteView routeView(AdminRows.Route route) {
        return new RouteView(route.dynamicId, route.upUid, route.allGroupId, route.upGroupId);
    }

    private AdminRows.Group requireGroup(long id) {
        AdminRows.Group group = mapper.group(id);
        if (group == null) throw notFound("群不存在");
        return group;
    }

    private AdminRows.Up requireUp(String uid) {
        if (!ID.matcher(uid).matches()) throw invalid("INVALID_UP", "UP UID 格式无效");
        AdminRows.Up up = mapper.up(uid);
        if (up == null) throw notFound("UP 不存在");
        return up;
    }

    private void validateGroups(Long ops, Long all, Long up, boolean requireOps) {
        if (requireOps && ops == null) throw invalid("INVALID_GROUP", "运维群不能为空");
        if (all == null && up == null) throw invalid("INVALID_ROUTE", "至少配置一个评论群");
        if (all != null && all.equals(up)) throw invalid("INVALID_ROUTE", "两个评论群不能相同");
        for (Long id : new Long[] {ops, all, up}) {
            if (id != null && (id <= 0 || mapper.group(id) == null)) {
                throw invalid("INVALID_GROUP", "所选群不存在");
            }
        }
    }

    private Long patchedGroup(JsonNode body, String field, Long existing) {
        if (!body.has(field)) return existing;
        JsonNode value = body.get(field);
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw invalid("INVALID_GROUP", "群 ID 格式无效");
        }
        return value.asLong();
    }

    private String name(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.trim().length() > 100) {
            throw invalid("INVALID_GROUP", "群名称需为 1～100 个字符");
        }
        return raw.trim();
    }

    private String webhook(String raw) {
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !WEBHOOK_HOSTS.contains(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !uri.getPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9-]+")) {
                throw invalid("INVALID_WEBHOOK", "请输入有效的飞书群 Webhook 地址");
            }
            return raw;
        } catch (URISyntaxException | NullPointerException error) {
            throw invalid("INVALID_WEBHOOK", "请输入有效的飞书群 Webhook 地址");
        }
    }

    private String parseId(String raw, boolean up) {
        if (raw == null) throw invalid(up ? "INVALID_UP" : "INVALID_DYNAMIC", "请输入数字 ID 或 B 站链接");
        String value = raw.trim();
        if (ID.matcher(value).matches()) return value;
        try {
            URI uri = new URI(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null) {
                throw new URISyntaxException(value, "invalid scheme");
            }
            String path = uri.getPath();
            if (path == null) throw new URISyntaxException(value, "missing path");
            String pattern = up ? "/([1-9][0-9]{0,31})/?" : "(?:/|/opus/|/dynamic/)([1-9][0-9]{0,31})/?";
            boolean hostAllowed = up ? "space.bilibili.com".equals(uri.getHost())
                    : Set.of("t.bilibili.com", "www.bilibili.com", "m.bilibili.com").contains(uri.getHost());
            Matcher match = Pattern.compile(pattern).matcher(path);
            if (hostAllowed && match.matches()) return match.group(1);
        } catch (URISyntaxException | NullPointerException ignored) {
            // A malformed link is handled by the same validation error as a malformed ID.
        }
        throw invalid(up ? "INVALID_UP" : "INVALID_DYNAMIC", "请输入数字 ID 或 B 站链接");
    }

    private static String utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static AdminException invalid(String code, String message) {
        return new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private static AdminException conflict(String code, String message) {
        return new AdminException(HttpStatus.CONFLICT, code, message);
    }

    private static AdminException notFound(String message) {
        return new AdminException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    record GroupView(Long id, String name, boolean webhookConfigured, boolean referenced, List<String> references) {}
    record UpView(String uid, String displayName, String avatarUrl, boolean enabled, Long opsGroupId,
                  Long defaultAllGroupId, Long defaultUpGroupId, String lastScanSucceededAt,
                  String lastScanError, long pendingCount, long failedCount) {}
    record RouteView(String dynamicId, String upUid, Long allGroupId, Long upGroupId) {}
    record UpStatus(String uid, boolean enabled, String workerHeartbeatAt, String lastScanStartedAt,
                    String lastScanSucceededAt, String lastScanErrorAt, String lastScanError,
                    long pendingCount, long failedCount) {}
}
