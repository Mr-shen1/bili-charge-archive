package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class AdminHttpTest extends MySqlTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BiliPreviewClient bili;

    @Test
    void cfgApiFlowAndNoPlaintextResponses() {
        String uid = "550494308";
        String dynamicId = "1251460007328743432";
        String webhook = "https://open.feishu.cn/open-apis/bot/v2/hook/http-test-only";
        HttpHeaders auth = login();
        try {
            ResponseEntity<Map> missingCsrf = http.exchange("/api/admin/groups", HttpMethod.POST,
                    new HttpEntity<>(Map.of("name", "m2-http"), cookieOnly(auth)), Map.class);
            assertThat(missingCsrf.getStatusCode().value()).isEqualTo(403);

            long ops = createGroup(auth, "m2-http-ops", webhook);
            long all = createGroup(auth, "m2-http-all", webhook);
            long own = createGroup(auth, "m2-http-own", webhook);
            assertThat(get("/api/admin/groups", auth).toString()).doesNotContain(webhook, "webhookCiphertext");
            assertThat(jdbc.queryForObject("SELECT webhook_ciphertext FROM feishu_group WHERE id=?", String.class, ops))
                    .startsWith("v1:").doesNotContain(webhook);

            when(bili.previewUser(uid)).thenReturn(new BiliPreviewClient.User(uid, "测试 UP", ""));
            assertThat(write("/api/admin/ups", HttpMethod.POST,
                    Map.of("uid", uid, "opsGroupId", ops), auth).getStatusCode().value()).isEqualTo(422);
            assertThat(write("/api/admin/ups", HttpMethod.POST,
                    Map.of("uid", uid, "opsGroupId", ops, "defaultAllGroupId", all, "defaultUpGroupId", all), auth)
                    .getStatusCode().value()).isEqualTo(422);
            assertThat(write("/api/admin/ups", HttpMethod.POST,
                    Map.of("uid", uid, "opsGroupId", ops, "defaultAllGroupId", all), auth)
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(get("/api/admin/ups/" + uid + "/status", auth).toString()).contains("pendingCount=0");
            assertThat(http.exchange("/api/admin/groups/" + ops, HttpMethod.DELETE,
                    new HttpEntity<>(auth), Map.class).getStatusCode().value()).isEqualTo(409);

            when(bili.previewDynamic(uid, dynamicId)).thenReturn(new BiliPreviewClient.Dynamic(
                    dynamicId, uid, "测试 UP", "测试动态", "DYNAMIC_TYPE_WORD", "MAJOR_TYPE_OPUS", "12345", 17));
            String routePath = "/api/admin/ups/" + uid + "/routes/" + dynamicId;
            assertThat(write(routePath, HttpMethod.PUT, Map.of(), auth).getStatusCode().value()).isEqualTo(422);
            assertThat(write(routePath, HttpMethod.PUT, Map.of("allGroupId", all, "upGroupId", all), auth)
                    .getStatusCode().value()).isEqualTo(422);
            assertThat(write(routePath, HttpMethod.PUT, Map.of("allGroupId", all, "upGroupId", own), auth)
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(get("/api/admin/ups/" + uid + "/routes", auth).toString()).contains(dynamicId);
            assertThat(http.exchange(routePath, HttpMethod.DELETE, new HttpEntity<>(auth), Map.class)
                    .getStatusCode().value()).isEqualTo(200);
        } finally {
            jdbc.update("DELETE FROM dynamic_route WHERE up_uid=?", uid);
            jdbc.update("DELETE FROM up_account WHERE uid=?", uid);
            jdbc.update("DELETE FROM feishu_group WHERE name LIKE 'm2-http-%'");
        }
    }

    private HttpHeaders login() {
        ResponseEntity<Map> response = http.postForEntity("/api/auth/login",
                Map.of("username", USERNAME, "password", PASSWORD), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0]);
        headers.add("X-CSRF-Token", (String) ((Map) response.getBody().get("data")).get("csrfToken"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders cookieOnly(HttpHeaders auth) {
        HttpHeaders result = new HttpHeaders();
        result.add(HttpHeaders.COOKIE, auth.getFirst(HttpHeaders.COOKIE));
        result.setContentType(MediaType.APPLICATION_JSON);
        return result;
    }

    private long createGroup(HttpHeaders auth, String name, String webhook) {
        ResponseEntity<Map> response = write("/api/admin/groups", HttpMethod.POST,
                Map.of("name", name, "webhook", webhook), auth);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return ((Number) ((Map) response.getBody().get("data")).get("id")).longValue();
    }

    private ResponseEntity<Map> write(String path, HttpMethod method, Map<String, ?> body, HttpHeaders auth) {
        return http.exchange(path, method, new HttpEntity<>(body, auth), Map.class);
    }

    private Map get(String path, HttpHeaders auth) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth), Map.class).getBody();
    }
}
