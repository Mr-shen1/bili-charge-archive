package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;

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

class MonitorHttpTest extends MySqlTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void enabledListConfigAndWorkerStatusUsePersistedState() {
        String uid = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String dynamicId = Long.toString(Math.abs(UUID.randomUUID().getLeastSignificantBits()));
        jdbc.update("INSERT INTO feishu_group(name,webhook_ciphertext) VALUES (?,?)", "m4-" + uid, "v1:test");
        long groupId = jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?", Long.class, "m4-" + uid);
        try {
            jdbc.update("INSERT INTO up_account(uid,display_name,enabled,ops_group_id,default_all_group_id) VALUES (?,?,1,?,?)",
                    uid, "M4 test", groupId, groupId);
            jdbc.update("INSERT INTO dynamic_route(dynamic_id,up_uid,all_group_id) VALUES (?,?,?)",
                    dynamicId, uid, groupId);
            jdbc.update("INSERT INTO dynamic(dynamic_id,up_uid,content_text,published_at,comment_oid,comment_type,first_seen_at,last_seen_at) VALUES (?,?,?,UTC_TIMESTAMP(),?,11,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                    dynamicId, uid, "test", dynamicId);
            jdbc.update("INSERT INTO dynamic_scan_state(dynamic_id,state_json) VALUES (?,?)",
                    dynamicId, "{\"knownIds\":[\"123\"]}");

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Monitor-Token", "m1-test-token-32-characters-long!");
            ResponseEntity<Map> listed = http.exchange("/internal/ups?enabled=true", HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<String>) listed.getBody().get("data")).contains(uid);

            ResponseEntity<Map> config = http.exchange("/internal/ups/" + uid + "/config", HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            Map data = (Map) config.getBody().get("data");
            assertThat(data.get("enabled")).isEqualTo(true);
            assertThat(((Iterable<?>) data.get("fixedRoutes"))).hasSize(1);
            assertThat(((Iterable<?>) data.get("scans"))).hasSize(1);

            headers.set("Content-Type", "application/json");
            ResponseEntity<Map> status = http.postForEntity("/internal/ups/" + uid + "/worker-status",
                    new HttpEntity<>(Map.of("kind", "SUCCEEDED"), headers), Map.class);
            assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc.queryForObject("SELECT last_scan_succeeded_at IS NOT NULL FROM up_account WHERE uid=?",
                    Boolean.class, uid)).isTrue();
        } finally {
            jdbc.update("DELETE FROM dynamic_route WHERE up_uid=?", uid);
            jdbc.update("DELETE FROM dynamic WHERE up_uid=?", uid);
            jdbc.update("DELETE FROM up_account WHERE uid=?", uid);
            jdbc.update("DELETE FROM feishu_group WHERE id=?", groupId);
        }
    }
}
