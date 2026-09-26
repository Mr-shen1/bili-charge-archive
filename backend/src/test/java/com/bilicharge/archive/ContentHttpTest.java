package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class ContentHttpTest extends MySqlTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void stablePagesStoredCountsUnavailableContentAndDisabledUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String uid = "91" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        String otherUid = "92" + uid.substring(2);
        long groupId = 0;
        String firstDynamic = "88000000000000000001";
        try {
            jdbc.update("INSERT INTO feishu_group(name,webhook_ciphertext) VALUES (?,?)", "m5-" + suffix, "fixture");
            groupId = jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?", Long.class, "m5-" + suffix);
            jdbc.update("INSERT INTO up_account(uid,display_name,enabled,ops_group_id,default_all_group_id) VALUES (?,'测试 UP',0,?,?)", uid, groupId, groupId);
            jdbc.update("INSERT INTO up_account(uid,display_name,enabled,ops_group_id,default_all_group_id) VALUES (?,'其他 UP',1,?,?)", otherUid, groupId, groupId);
            for (int i = 1; i <= 21; i++) {
                String id = "880000000000000000" + String.format("%02d", i);
                jdbc.update("""
                        INSERT INTO dynamic(dynamic_id,up_uid,content_text,published_at,comment_oid,comment_type,
                                            source_unavailable_at,first_seen_at,last_seen_at)
                        VALUES (?,?,?,'2026-09-25 08:00:00',?,11,IF(?=1,UTC_TIMESTAMP(3),NULL),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                        """, id, uid, "动态 " + i, id, i);
            }
            String otherDynamic = "99000000000000000001";
            jdbc.update("""
                    INSERT INTO dynamic(dynamic_id,up_uid,content_text,published_at,comment_oid,comment_type,first_seen_at,last_seen_at)
                    VALUES (?,?,'其他动态','2026-09-26 08:00:00',?,11,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                    """, otherDynamic, otherUid, otherDynamic);
            for (int i = 1; i <= 21; i++) {
                String rpid = "770000000000000000" + String.format("%02d", i);
                jdbc.update("""
                        INSERT INTO comment(dynamic_id,rpid,author_mid,author_name,content_text,published_at,
                                            source_unavailable_at,first_seen_at,last_seen_at)
                        VALUES (?,?,?,'读者',?,'2026-09-25 09:00:00',IF(?=1,UTC_TIMESTAMP(3),NULL),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                        """, firstDynamic, rpid, uid, "根评论 " + i, i);
                String reply = "660000000000000000" + String.format("%02d", i);
                jdbc.update("""
                        INSERT INTO comment(dynamic_id,rpid,root_rpid,parent_rpid,author_mid,author_name,
                                            content_text,published_at,first_seen_at,last_seen_at)
                        VALUES (?,?,?,?,?,'回复者',?,'2026-09-25 10:00:00',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                        """, firstDynamic, reply, "77000000000000000001", "77000000000000000001",
                        uid, "楼中楼 " + i);
            }
            jdbc.update("INSERT INTO dynamic_image(dynamic_id,position,source_url,upload_status,oss_key) VALUES (?,0,'https://example.invalid/p.jpg','READY','fixture-key')", firstDynamic);
            jdbc.update("INSERT INTO comment_image(dynamic_id,rpid,position,source_url) VALUES (?,'77000000000000000001',0,'https://example.invalid/c.jpg')", firstDynamic);

            assertThat(http.getForEntity("/api/dynamics", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            String cookie = login();
            Map all = get("/api/dynamics", cookie);
            assertThat(data(all)).hasSize(20);
            assertThat(data(all).getFirst().get("dynamicId")).isEqualTo(otherDynamic);
            Map filtered = get("/api/dynamics?upUid=" + uid, cookie);
            assertThat(data(filtered)).hasSize(20);
            assertThat(data(filtered).getFirst().get("dynamicId")).isEqualTo("88000000000000000021");
            Map second = get("/api/dynamics?upUid=" + uid + "&cursor=" + enc(next(filtered)), cookie);
            assertThat(data(second)).hasSize(1);
            assertThat(data(second).getFirst().get("dynamicId")).isEqualTo(firstDynamic);
            assertThat(data(get("/api/dynamics?upUid=" + uid + "&cursor=" + enc(prev(second)), cookie)))
                    .extracting(row -> row.get("dynamicId")).containsExactlyElementsOf(
                            data(filtered).stream().map(row -> row.get("dynamicId")).toList());
            assertThat(get("/api/dynamics?upUid=" + otherUid + "&cursor=" + enc(next(filtered)), cookie).get("code"))
                    .isEqualTo("INVALID_CURSOR");

            Map detail = (Map) get("/api/dynamics/" + firstDynamic, cookie).get("data");
            assertThat(detail.get("upEnabled")).isEqualTo(false);
            assertThat(detail.get("sourceUnavailable")).isEqualTo(true);
            assertThat(detail.get("storedCommentCount")).isEqualTo(42);
            assertThat((Map) ((List) detail.get("images")).getFirst()).containsEntry("status", "READY")
                    .containsEntry("url", "/api/media/dynamics/" + firstDynamic + "/0");
            Map roots = get("/api/dynamics/" + firstDynamic + "/comments", cookie);
            assertThat(data(roots)).hasSize(20);
            assertThat(data(roots).getFirst().get("rpid")).isEqualTo("77000000000000000021");
            Map lastRoot = get("/api/dynamics/" + firstDynamic + "/comments?cursor=" + enc(next(roots)), cookie);
            assertThat(data(lastRoot)).hasSize(1);
            assertThat(data(lastRoot).getFirst().get("sourceUnavailable")).isEqualTo(true);
            assertThat(data(lastRoot).getFirst().get("storedReplyCount")).isEqualTo(21);
            assertThat(data(get("/api/dynamics/" + firstDynamic + "/comments?cursor=" + enc(prev(lastRoot)), cookie)))
                    .extracting(row -> row.get("rpid")).containsExactlyElementsOf(
                            data(roots).stream().map(row -> row.get("rpid")).toList());
            String repliesPath = "/api/dynamics/" + firstDynamic + "/comments/77000000000000000001/replies";
            Map replies = get(repliesPath, cookie);
            assertThat(data(replies)).hasSize(20);
            assertThat(data(replies).getFirst().get("rpid")).isEqualTo("66000000000000000001");
            Map lastReply = get(repliesPath + "?cursor=" + enc(next(replies)), cookie);
            assertThat(data(lastReply)).hasSize(1);
            assertThat(data(lastReply).getFirst().get("rpid")).isEqualTo("66000000000000000021");
            assertThat(data(get(repliesPath + "?cursor=" + enc(prev(lastReply)), cookie)))
                    .extracting(row -> row.get("rpid")).containsExactlyElementsOf(
                            data(replies).stream().map(row -> row.get("rpid")).toList());
            assertThat(new HashSet<>(data(replies).stream().map(row -> row.get("rpid")).toList()))
                    .doesNotContain(data(lastReply).getFirst().get("rpid"));
            assertThat(get("/api/ups", cookie).get("data").toString()).contains(uid, otherUid);
        } finally {
            jdbc.update("DELETE FROM comment_image WHERE dynamic_id LIKE '880000000000000000%'");
            jdbc.update("DELETE FROM dynamic_image WHERE dynamic_id LIKE '880000000000000000%'");
            jdbc.update("DELETE FROM comment WHERE dynamic_id LIKE '880000000000000000%'");
            jdbc.update("DELETE FROM dynamic WHERE up_uid IN (?,?)", uid, otherUid);
            jdbc.update("DELETE FROM up_account WHERE uid IN (?,?)", uid, otherUid);
            if (groupId != 0) jdbc.update("DELETE FROM feishu_group WHERE id=?", groupId);
        }
    }

    private String login() {
        ResponseEntity<Map> response = http.postForEntity("/api/auth/login",
                Map.of("username", USERNAME, "password", PASSWORD), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];
    }

    private Map get(String path, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map> data(Map response) { return (List<Map>) response.get("data"); }
    private String next(Map response) { return (String) ((Map) response.get("page")).get("nextCursor"); }
    private String prev(Map response) { return (String) ((Map) response.get("page")).get("prevCursor"); }
    private String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
