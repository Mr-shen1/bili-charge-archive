package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class BatchHttpTest extends MySqlTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void baselineWaitsForCompletionAndLaterCommentsAreReady() {
        Fixture fixture = fixture(true);
        try {
            String id = fixture.dynamicId;
            BatchRequest.Item first = item(fixture, "原文", List.of(
                    comment("701", fixture.uid, "UP 根评论", List.of("https://i.example/up.jpg")),
                    comment("702", "300", "普通根评论", List.of())),
                    List.of("https://i.example/dynamic.jpg"));
            Map result = submit(fixture, batch(List.of(first), List.of(), List.of()));
            assertThat(result.get("newDynamics")).isEqualTo(1);
            assertThat(result.get("newComments")).isEqualTo(2);
            assertThat(result.get("newEvents")).isEqualTo(3);
            assertThat(count("dynamic", "dynamic_id", id)).isEqualTo(1);
            assertThat(count("comment", "dynamic_id", id)).isEqualTo(2);
            assertThat(count("dynamic_scan_state", "dynamic_id", id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dynamic_id=? AND ready_at IS NULL",
                    Integer.class, id)).isEqualTo(3);

            BatchRequest.Item last = item(fixture, "原文", List.of(
                    nestedComment("703", "701", fixture.uid, "UP 楼中楼")), null);
            result = submit(fixture, batch(List.of(last), List.of(id), List.of()));
            assertThat(result.get("newDynamics")).isEqualTo(0);
            assertThat(result.get("newComments")).isEqualTo(1);
            assertThat(result.get("newEvents")).isEqualTo(1);
            assertThat(result.get("releasedEvents")).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dynamic_id=? AND ready_at IS NOT NULL",
                    Integer.class, id)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT baseline_completed_at IS NOT NULL FROM dynamic_scan_state WHERE dynamic_id=?",
                    Boolean.class, id)).isTrue();

            BatchRequest.Item later = item(fixture, "原文", List.of(
                    comment("704", "301", "后续普通评论", List.of())), null);
            submit(fixture, batch(List.of(later), List.of(), List.of()));
            assertThat(jdbc.queryForObject("SELECT ready_at IS NOT NULL FROM notification_event WHERE dedupe_key=?",
                    Boolean.class, "comment:" + id + ":704")).isTrue();
            assertThat(jdbc.queryForObject("SELECT is_up_comment FROM notification_event WHERE dedupe_key=?",
                    Boolean.class, "comment:" + id + ":703")).isTrue();
            assertThat(jdbc.queryForObject("SELECT root_rpid FROM comment WHERE dynamic_id=? AND rpid='703'",
                    String.class, id)).isEqualTo("701");
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void replayEditImagesAndAvailabilityDoNotCreateNewEvents() {
        Fixture fixture = fixture(false);
        try {
            String id = fixture.dynamicId;
            BatchRequest.Item original = item(fixture, "第一版", List.of(
                    comment("801", fixture.uid, "原评论", List.of("https://i.example/c1.jpg")),
                    comment("802", "300", "普通评论", List.of())),
                    List.of("https://i.example/d1.jpg", "https://i.example/d2.jpg"));
            BatchRequest request = batch(List.of(original), List.of(id), List.of());
            submit(fixture, request);
            Map replay = submit(fixture, request);
            assertThat(replay.get("newDynamics")).isEqualTo(0);
            assertThat(replay.get("newComments")).isEqualTo(0);
            assertThat(replay.get("newEvents")).isEqualTo(0);
            assertThat(replay.get("releasedEvents")).isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dynamic_id=?",
                    Integer.class, id)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dedupe_key=?",
                    Integer.class, "comment:" + id + ":802")).isZero();

            jdbc.update("UPDATE dynamic_image SET upload_status='READY',oss_key='old-key' WHERE dynamic_id=? AND position=0", id);
            jdbc.update("UPDATE comment_image SET upload_status='READY',oss_key='old-comment-key' WHERE dynamic_id=? AND rpid='801'", id);
            BatchRequest.Item edited = item(fixture, "第二版", List.of(
                    comment("801", fixture.uid, "新评论", List.of("https://i.example/c1.jpg"))),
                    List.of("https://i.example/new.jpg"));
            submit(fixture, batch(List.of(edited), List.of(), List.of()));
            assertThat(jdbc.queryForObject("SELECT content_text FROM dynamic WHERE dynamic_id=?", String.class, id))
                    .isEqualTo("第二版");
            assertThat(jdbc.queryForObject("SELECT content_text FROM comment WHERE dynamic_id=? AND rpid='801'",
                    String.class, id)).isEqualTo("新评论");
            assertThat(jdbc.queryForObject("SELECT message_text FROM notification_event WHERE dedupe_key=?",
                    String.class, "dynamic:" + id)).isEqualTo("通知：第一版");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dynamic_id=?",
                    Integer.class, id)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT upload_status FROM dynamic_image WHERE dynamic_id=? AND position=0",
                    String.class, id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT oss_key FROM dynamic_image WHERE dynamic_id=? AND position=0",
                    String.class, id)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_image WHERE dynamic_id=?", Integer.class, id))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT upload_status FROM comment_image WHERE dynamic_id=? AND rpid='801'",
                    String.class, id)).isEqualTo("READY");

            submit(fixture, batch(List.of(), List.of(), List.of(
                    new BatchRequest.AvailabilityChange(id, null, true),
                    new BatchRequest.AvailabilityChange(id, "801", true))));
            assertThat(jdbc.queryForObject("SELECT source_unavailable_at IS NOT NULL FROM dynamic WHERE dynamic_id=?",
                    Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT source_unavailable_at IS NOT NULL FROM comment WHERE dynamic_id=? AND rpid='801'",
                    Boolean.class, id)).isTrue();
            submit(fixture, batch(List.of(edited), List.of(), List.of()));
            assertThat(jdbc.queryForObject("SELECT source_unavailable_at IS NULL FROM dynamic WHERE dynamic_id=?",
                    Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT source_unavailable_at IS NULL FROM comment WHERE dynamic_id=? AND rpid='801'",
                    Boolean.class, id)).isTrue();
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void mysqlFailureAfterContentAndProgressRollsBackThenExactRetrySucceeds() {
        Fixture fixture = fixture(true);
        String constraint = "chk_m3_injected_failure";
        boolean constraintAdded = false;
        try {
            jdbc.execute("ALTER TABLE notification_event ADD CONSTRAINT " + constraint
                    + " CHECK (dedupe_key <> 'dynamic:" + fixture.dynamicId + "')");
            constraintAdded = true;
            BatchRequest request = batch(List.of(item(fixture, "失败后重试", List.of(
                    comment("901", fixture.uid, "评论", List.of())), List.of("https://i.example/image.jpg"))),
                    List.of(fixture.dynamicId), List.of());
            ResponseEntity<Map> failed = post(fixture, request);
            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(count("dynamic", "dynamic_id", fixture.dynamicId)).isZero();
            assertThat(count("comment", "dynamic_id", fixture.dynamicId)).isZero();
            assertThat(count("dynamic_image", "dynamic_id", fixture.dynamicId)).isZero();
            assertThat(count("dynamic_scan_state", "dynamic_id", fixture.dynamicId)).isZero();
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isZero();

            jdbc.execute("ALTER TABLE notification_event DROP CHECK " + constraint);
            constraintAdded = false;
            Map result = submit(fixture, request);
            assertThat(result.get("newDynamics")).isEqualTo(1);
            assertThat(result.get("newComments")).isEqualTo(1);
            assertThat(result.get("newEvents")).isEqualTo(2);
            assertThat(result.get("releasedEvents")).isEqualTo(2);
            assertThat(count("dynamic", "dynamic_id", fixture.dynamicId)).isEqualTo(1);
            assertThat(count("comment", "dynamic_id", fixture.dynamicId)).isEqualTo(1);
            assertThat(count("dynamic_scan_state", "dynamic_id", fixture.dynamicId)).isEqualTo(1);
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dynamic_id=? AND ready_at IS NOT NULL",
                    Integer.class, fixture.dynamicId)).isEqualTo(2);

            jdbc.execute("ALTER TABLE notification_event ADD CONSTRAINT " + constraint
                    + " CHECK (dedupe_key <> 'comment:" + fixture.dynamicId + ":902')");
            constraintAdded = true;
            BatchRequest.Item later = item(fixture, "失败后重试", List.of(
                    comment("902", fixture.uid, "新评论", List.of())), null);
            later = new BatchRequest.Item(later.dynamic(), later.comments(),
                    new BatchRequest.ScanState(json.createObjectNode().put("cursor", "later"),
                            "2026-09-25T10:00:00Z", null, null), later.eventCandidates());
            BatchRequest incremental = batch(List.of(later), List.of(), List.of());
            assertThat(post(fixture, incremental).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.cursor')) "
                    + "FROM dynamic_scan_state WHERE dynamic_id=?", String.class, fixture.dynamicId))
                    .isEqualTo("next");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE dynamic_id=? AND rpid='902'",
                    Integer.class, fixture.dynamicId)).isZero();
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isEqualTo(2);
            jdbc.execute("ALTER TABLE notification_event DROP CHECK " + constraint);
            constraintAdded = false;
            submit(fixture, incremental);
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.cursor')) "
                    + "FROM dynamic_scan_state WHERE dynamic_id=?", String.class, fixture.dynamicId))
                    .isEqualTo("later");
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isEqualTo(3);
        } finally {
            if (constraintAdded) jdbc.execute("ALTER TABLE notification_event DROP CHECK " + constraint);
            fixture.cleanup();
        }
    }

    @Test
    void invalidSecondItemAndDisabledUpCannotPartiallyAdvanceBatch() {
        Fixture fixture = fixture(true);
        try {
            BatchRequest.Item valid = item(fixture, "第一条", List.of(), List.of());
            BatchRequest.Item missingCandidate = new BatchRequest.Item(valid.dynamic(), valid.comments(),
                    valid.scanState(), List.of());
            assertThat(post(fixture, batch(List.of(missingCandidate), List.of(), List.of())).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(count("dynamic", "dynamic_id", fixture.dynamicId)).isZero();
            BatchRequest.Item invalid = new BatchRequest.Item(
                    new BatchRequest.Dynamic("998877665544332211", "123456", null, "错归属",
                            "2026-09-25T08:00:00Z", "123", 11, List.of()),
                    List.of(), new BatchRequest.ScanState(json.createObjectNode(), null, null, null),
                    List.of());
            ResponseEntity<Map> rejected = post(fixture, batch(List.of(valid, invalid), List.of(), List.of()));
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(count("dynamic", "dynamic_id", fixture.dynamicId)).isZero();
            assertThat(count("dynamic_scan_state", "dynamic_id", fixture.dynamicId)).isZero();
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isZero();

            jdbc.update("UPDATE up_account SET enabled=0 WHERE uid=?", fixture.uid);
            ResponseEntity<Map> disabled = post(fixture, batch(List.of(valid), List.of(), List.of()));
            assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(count("dynamic", "dynamic_id", fixture.dynamicId)).isZero();
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void dedicatedRouteOverridesDefaultWithoutBackfillingProcessedComments() {
        Fixture fixture = fixture(true);
        try {
            long allGroup = jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?",
                    Long.class, fixture.groupName);
            long upGroup = jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?",
                    Long.class, fixture.groupName + "-up");
            jdbc.update("INSERT INTO dynamic_route(dynamic_id,up_uid,all_group_id,up_group_id) VALUES(?,?,NULL,?)",
                    fixture.dynamicId, fixture.uid, upGroup);
            BatchRequest.Item first = item(fixture, "专属路由", List.of(
                    comment("1001", "300", "普通评论", List.of()),
                    comment("1002", fixture.uid, "UP 评论", List.of())), List.of());
            BatchRequest request = batch(List.of(first), List.of(fixture.dynamicId), List.of());
            submit(fixture, request);
            assertThat(count("comment", "dynamic_id", fixture.dynamicId)).isEqualTo(2);
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dedupe_key=?",
                    Integer.class, "comment:" + fixture.dynamicId + ":1001")).isZero();

            jdbc.update("UPDATE dynamic_route SET all_group_id=?,up_group_id=NULL WHERE dynamic_id=?",
                    allGroup, fixture.dynamicId);
            submit(fixture, request);
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isEqualTo(2);
            submit(fixture, batch(List.of(item(fixture, "专属路由", List.of(
                    comment("1003", "301", "后续普通评论", List.of())), null)), List.of(), List.of()));
            assertThat(jdbc.queryForObject("SELECT ready_at IS NOT NULL FROM notification_event WHERE dedupe_key=?",
                    Boolean.class, "comment:" + fixture.dynamicId + ":1003")).isTrue();
            assertThat(count("notification_event", "dynamic_id", fixture.dynamicId)).isEqualTo(3);
        } finally {
            fixture.cleanup();
        }
    }

    private BatchRequest.Item item(Fixture fixture, String text, List<BatchRequest.Comment> comments,
                                   List<String> images) {
        List<BatchRequest.EventCandidate> candidates = new ArrayList<>();
        candidates.add(new BatchRequest.EventCandidate("dynamic:" + fixture.dynamicId, "DYNAMIC", null,
                "通知：" + text, images));
        for (BatchRequest.Comment comment : comments) {
            candidates.add(new BatchRequest.EventCandidate("comment:" + fixture.dynamicId + ":" + comment.rpid(),
                    "COMMENT", comment.rpid(), "通知：" + comment.text(), comment.images()));
        }
        return new BatchRequest.Item(new BatchRequest.Dynamic(fixture.dynamicId, fixture.uid, null,
                text, "2026-09-25T08:00:00Z", "410108956", 11, images), comments,
                new BatchRequest.ScanState(json.createObjectNode().put("cursor", "next"),
                        "2026-09-25T09:00:00Z", null, null), candidates);
    }

    private BatchRequest.Comment comment(String rpid, String author, String text, List<String> images) {
        return new BatchRequest.Comment(rpid, null, null, author, "评论者", null, 0,
                text, "2026-09-25T08:01:00Z", 0L, 0L, images);
    }

    private BatchRequest.Comment nestedComment(String rpid, String rootRpid, String author, String text) {
        return new BatchRequest.Comment(rpid, rootRpid, rootRpid, author, "评论者", null, 0,
                text, "2026-09-25T08:02:00Z", 0L, 0L, List.of());
    }

    private BatchRequest batch(List<BatchRequest.Item> items, List<String> completed,
                               List<BatchRequest.AvailabilityChange> availability) {
        return new BatchRequest(items, completed, availability);
    }

    private Map submit(Fixture fixture, BatchRequest request) {
        ResponseEntity<Map> response = post(fixture, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map) response.getBody().get("data");
    }

    private ResponseEntity<Map> post(Fixture fixture, BatchRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Monitor-Token", "m1-test-token-32-characters-long!");
        return http.postForEntity("/internal/ups/" + fixture.uid + "/batches",
                new HttpEntity<>(request, headers), Map.class);
    }

    private int count(String table, String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
    }

    private Fixture fixture(boolean allAndUp) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String uid = "8" + suffix.substring(0, 18).replaceAll("[a-f]", "1");
        String dynamicId = "9" + suffix.substring(0, 18).replaceAll("[a-f]", "2");
        String groupName = "m3-test-" + suffix;
        jdbc.update("INSERT INTO feishu_group(name,webhook_ciphertext) VALUES (?,?)", groupName, "test-only");
        long all = jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?", Long.class, groupName);
        Long up = null;
        if (allAndUp) {
            jdbc.update("INSERT INTO feishu_group(name,webhook_ciphertext) VALUES (?,?)", groupName + "-up", "test-only");
            up = jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?", Long.class, groupName + "-up");
        }
        jdbc.update("""
                INSERT INTO up_account(uid,display_name,enabled,ops_group_id,default_all_group_id,default_up_group_id)
                VALUES(?, 'M3 测试 UP', 1, ?, ?, ?)
                """, uid, all, allAndUp ? all : null, allAndUp ? up : all);
        return new Fixture(uid, dynamicId, groupName);
    }

    private final class Fixture {
        final String uid;
        final String dynamicId;
        final String groupName;

        Fixture(String uid, String dynamicId, String groupName) {
            this.uid = uid;
            this.dynamicId = dynamicId;
            this.groupName = groupName;
        }

        void cleanup() {
            jdbc.update("DELETE FROM notification_event WHERE up_uid=?", uid);
            jdbc.update("DELETE FROM dynamic WHERE up_uid=?", uid);
            jdbc.update("DELETE FROM dynamic_route WHERE up_uid=?", uid);
            jdbc.update("DELETE FROM up_account WHERE uid=?", uid);
            jdbc.update("DELETE FROM feishu_group WHERE name IN (?,?)", groupName, groupName + "-up");
        }
    }
}
