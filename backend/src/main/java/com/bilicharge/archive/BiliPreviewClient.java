package com.bilicharge.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class BiliPreviewClient {
    private static final Set<String> CONTENT_TYPES = Set.of("DYNAMIC_TYPE_WORD", "DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_ARTICLE");
    private static final Set<String> MAJOR_TYPES = Set.of("MAJOR_TYPE_OPUS", "MAJOR_TYPE_DRAW");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String cookie;

    BiliPreviewClient(ObjectMapper json, @Value("${app.bili.cookie:}") String cookie) {
        this.json = json;
        this.cookie = cookie;
    }

    User previewUser(String uid) {
        JsonNode root = get("https://api.bilibili.com/x/web-interface/card?mid=" + uid, false);
        JsonNode card = root.path("data").path("card");
        if (!uid.equals(card.path("mid").asText()) || card.path("name").asText().isBlank()) {
            throw invalid("无法核实该 UP 的资料");
        }
        return new User(uid, card.path("name").asText(), card.path("face").asText(""));
    }

    Dynamic previewDynamic(String uid, String dynamicId) {
        if (cookie.isBlank()) {
            throw new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "BILI_COOKIE_MISSING", "尚未配置 B 站测试账号 Cookie");
        }
        JsonNode root = get("https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=" + dynamicId
                + "&timezone_offset=-480&platform=web", true);
        return parseDynamic(root.path("data").path("item"), uid, dynamicId);
    }

    static Dynamic parseDynamic(JsonNode item, String uid, String dynamicId) {
        if (!dynamicId.equals(item.path("id_str").asText())) throw invalid("动态 ID 与来源不一致");
        JsonNode author = item.path("modules").path("module_author");
        if (!uid.equals(author.path("mid").asText())) throw invalid("动态不属于该 UP");
        String badge = author.path("icon_badge").path("text").asText();
        if (!"充电专属".equals(badge)) throw invalid("无法确认这是一条充电专属动态");
        String type = item.path("type").asText();
        JsonNode major = item.path("modules").path("module_dynamic").path("major");
        String majorType = major.path("type").asText();
        if (!CONTENT_TYPES.contains(type) || !MAJOR_TYPES.contains(majorType)) {
            throw invalid("只支持充电专属文字或图片动态");
        }
        JsonNode basic = item.path("basic");
        String oid = basic.path("comment_id_str").asText();
        int commentType = basic.path("comment_type").asInt(-1);
        if (!oid.matches("[0-9]{1,32}") || commentType <= 0 || commentType > 65535) {
            throw invalid("动态缺少有效评论目标");
        }
        String title = major.path("opus").path("title").asText("");
        return new Dynamic(dynamicId, uid, author.path("name").asText(""), title,
                type, majorType, oid, commentType);
    }

    private JsonNode get(String url, boolean authenticated) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.bilibili.com/")
                    .GET();
            if (authenticated) request.header("Cookie", cookie);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw upstream();
            JsonNode root = json.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) throw upstream();
            return root;
        } catch (IOException error) {
            throw upstream();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw upstream();
        }
    }

    private static AdminException invalid(String message) {
        return new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DYNAMIC", message);
    }

    private static AdminException upstream() {
        return new AdminException(HttpStatus.BAD_GATEWAY, "BILI_PREVIEW_FAILED", "B 站预览暂不可用或无权访问");
    }

    record User(String uid, String displayName, String avatarUrl) {}
    record Dynamic(String dynamicId, String upUid, String upName, String title,
                   String type, String majorType, String commentOid, int commentType) {}
}
