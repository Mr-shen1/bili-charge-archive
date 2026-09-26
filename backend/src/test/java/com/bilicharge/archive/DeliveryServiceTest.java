package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DeliveryServiceTest extends MySqlTestBase {
    @Autowired DeliveryService service;
    @Autowired OpsEventService ops;
    @Autowired AdminService admin;
    @Autowired AdminMapper adminMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    @Test
    void routesDynamicAndCommentsByRoleAndKeepsSentTargetAfterChange() {
        Fixture f = fixture();
        long d = event(f, "DYNAMIC", null, false);
        long ordinary = event(f, "COMMENT", "71", false);
        long own = event(f, "COMMENT", "72", true);
        DeliveryService.Claim first = service.claim(null, "workerA");
        assertThat(first.eventId()).isEqualTo(d);
        assertThat(first.role()).isEqualTo("ALL");
        assertThat(first.webhook()).contains("/hook/test-");
        service.result(d, "ALL", new DeliveryService.Result("workerA", first.leaseToken(), true, null));

        // Unsent roles follow the current dedicated route; the sent ALL snapshot remains unchanged.
        AdminRows.Route route = new AdminRows.Route();
        route.dynamicId = f.dynamicId;
        route.upUid = f.uid;
        route.allGroupId = f.newAll;
        route.upGroupId = f.newUp;
        adminMapper.insertRoute(route);
        DeliveryService.Claim second = service.claim(null, "workerA");
        assertThat(second.eventId()).isEqualTo(d);
        assertThat(second.role()).isEqualTo("UP");
        assertThat(group(d, "ALL")).isEqualTo(f.all);
        assertThat(group(d, "UP")).isEqualTo(f.newUp);
        service.result(d, "UP", new DeliveryService.Result("workerA", second.leaseToken(), true, null));
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM notification_event WHERE id=?",
                Boolean.class, d)).isTrue();

        DeliveryService.Claim third = service.claim(null, "workerA");
        assertThat(third.eventId()).isEqualTo(ordinary);
        assertThat(third.role()).isEqualTo("ALL");
        assertThat(group(ordinary, "ALL")).isEqualTo(f.newAll);
        service.result(ordinary, "ALL", new DeliveryService.Result("workerA", third.leaseToken(), true, null));
        DeliveryService.Claim fourth = service.claim(null, "workerA");
        assertThat(fourth.eventId()).isEqualTo(own);
        assertThat(fourth.role()).isEqualTo("ALL");
        service.result(own, "ALL", new DeliveryService.Result("workerA", fourth.leaseToken(), true, null));
        DeliveryService.Claim fifth = service.claim(null, "workerA");
        assertThat(fifth.eventId()).isEqualTo(own);
        assertThat(fifth.role()).isEqualTo("UP");
        service.result(own, "UP", new DeliveryService.Result("workerA", fifth.leaseToken(), true, null));
        adminMapper.deleteRoute(f.uid, f.dynamicId);
        assertThat(service.claim(null, "workerA")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE event_id=?", Integer.class,
                ordinary)).isEqualTo(1);
    }

    @Test
    void retryBlocksSameGroupAndExpiredLeaseCanBeReclaimed() {
        Fixture f = fixture();
        long firstId = event(f, "COMMENT", "81", false);
        long secondId = event(f, "COMMENT", "82", false);
        DeliveryService.Claim first = service.claim(null, "workerB");
        assertThat(first.eventId()).isEqualTo(firstId);
        service.result(firstId, "ALL", new DeliveryService.Result("workerB", first.leaseToken(), false, "HTTP_500"));
        LocalDateTime next = jdbc.queryForObject("SELECT next_retry_at FROM notification_delivery WHERE event_id=? AND role='ALL'",
                LocalDateTime.class, firstId);
        assertThat(next).isBetween(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(3),
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(6));
        assertThat(service.claim(null, "workerB")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE event_id=?", Integer.class,
                secondId)).isEqualTo(1);
        jdbc.update("UPDATE notification_delivery SET next_retry_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE event_id=?",
                firstId);
        DeliveryService.Claim retried = service.claim(null, "workerB");
        assertThat(retried.eventId()).isEqualTo(firstId);
        jdbc.update("UPDATE notification_delivery SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE event_id=?",
                firstId);
        DeliveryService.Claim reclaimed = service.claim(null, "workerC");
        assertThat(reclaimed.eventId()).isEqualTo(firstId);
        assertThat(reclaimed.leaseToken()).isNotEqualTo(retried.leaseToken());
        assertThatThrownBy(() -> service.result(firstId, "ALL",
                new DeliveryService.Result("workerB", retried.leaseToken(), true, null)))
                .isInstanceOf(AdminException.class)
                .satisfies(error -> assertThat(((AdminException) error).code()).isEqualTo("LEASE_EXPIRED"));
        service.result(firstId, "ALL", new DeliveryService.Result("workerC", reclaimed.leaseToken(), true, null));
        assertThat(service.claim(null, "workerC").eventId()).isEqualTo(secondId);
    }

    @Test
    void retryLadderAndSharedGroupOrderingAcrossUps() {
        Fixture first = fixture();
        Fixture second = fixture();
        jdbc.update("UPDATE up_account SET default_all_group_id=? WHERE uid=?", first.all, second.uid);
        long head = event(first, "COMMENT", "91", false);
        long tail = event(second, "COMMENT", "92", false);
        int[] delays = {5, 10, 15, 30, 60, 60};
        for (int delay : delays) {
            DeliveryService.Claim claim = service.claim(null, "workerE");
            assertThat(claim.eventId()).isEqualTo(head);
            assertThat(service.claim(null, "workerE")).isNull();
            service.result(head, "ALL", new DeliveryService.Result("workerE", claim.leaseToken(), false, "HTTP_500"));
            LocalDateTime next = jdbc.queryForObject("SELECT next_retry_at FROM notification_delivery WHERE event_id=?",
                    LocalDateTime.class, head);
            assertThat(next).isBetween(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delay - 2),
                    LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delay + 1));
            assertThat(service.claim(null, "workerE")).isNull();
            jdbc.update("UPDATE notification_delivery SET next_retry_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE event_id=?",
                    head);
            sqlSession.clearCache();
        }
        DeliveryService.Claim recovered = service.claim(null, "workerE");
        service.result(head, "ALL", new DeliveryService.Result("workerE", recovered.leaseToken(), true, null));
        assertThat(service.claim(null, "workerE").eventId()).isEqualTo(tail);
    }

    @Test
    void opsErrorsArePerRoundHeartbeatIsHourlyAndCleanupPreservesPending() {
        Fixture f = fixture();
        ops.status(f.uid, "STARTED", null);
        ops.status(f.uid, "STARTED", null);
        ops.status(f.uid, "ERROR", "网络失败");
        ops.status(f.uid, "ERROR", "网络失败");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE up_uid=? AND event_type='OPS_HEARTBEAT'",
                Integer.class, f.uid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE up_uid=? AND event_type='OPS_ERROR'",
                Integer.class, f.uid)).isEqualTo(2);
        DeliveryService.Claim opsClaim = service.claim(null, "workerD");
        assertThat(opsClaim.role()).isEqualTo("OPS");
        assertThat(group(opsClaim.eventId(), "OPS")).isEqualTo(f.ops);
        service.result(opsClaim.eventId(), "OPS",
                new DeliveryService.Result("workerD", opsClaim.leaseToken(), true, null));
        assertThat(service.recent(f.uid, "SENT", null).data()).hasSize(1);
        jdbc.update("UPDATE notification_event SET completed_at=UTC_TIMESTAMP(3)-INTERVAL 91 DAY WHERE id=?",
                opsClaim.eventId());
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE up_uid=?", Integer.class,
                f.uid)).isEqualTo(2);
    }

    @Test
    void deliveryHistoryUsesStableScopeBoundCursors() {
        Fixture f = fixture();
        for (int i = 0; i < 21; i++) {
            long id = event(f, "COMMENT", Integer.toString(100 + i), false);
            jdbc.update("UPDATE notification_event SET completed_at=UTC_TIMESTAMP(3) WHERE id=?", id);
            jdbc.update("""
                    INSERT INTO notification_delivery(event_id,role,group_id,group_name_snapshot,status,sent_at)
                    VALUES (?,'ALL',?,'测试群','SENT',UTC_TIMESTAMP(3))
                    """, id, f.all);
        }
        PageEnvelope<DeliveryService.Summary> first = service.recent(f.uid, "SENT", null);
        assertThat(first.data()).hasSize(20);
        assertThat(first.page().hasNext()).isTrue();
        PageEnvelope<DeliveryService.Summary> last = service.recent(f.uid, "SENT", first.page().nextCursor());
        assertThat(last.data()).hasSize(1);
        assertThat(last.data().getFirst().eventId()).isLessThan(first.data().getLast().eventId());
        PageEnvelope<DeliveryService.Summary> back = service.recent(f.uid, "SENT", last.page().prevCursor());
        assertThat(back.data()).extracting(DeliveryService.Summary::eventId)
                .containsExactlyElementsOf(first.data().stream().map(DeliveryService.Summary::eventId).toList());
        assertThatThrownBy(() -> service.recent(f.uid, "PENDING", first.page().nextCursor()))
                .isInstanceOf(AdminException.class);
    }

    @Test
    void canceledHistoricalEventIsNeverClaimed() {
        Fixture f = fixture();
        long canceled = event(f, "DYNAMIC", null, false);
        jdbc.update("UPDATE notification_event SET canceled_at=UTC_TIMESTAMP(3) WHERE id=?", canceled);
        assertThat(service.claim(null, "workerF")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE event_id=?",
                Integer.class, canceled)).isZero();
    }

    @Test
    void activeLeaseKeepsItsOriginalGroupUntilResultArrives() {
        Fixture f = fixture();
        long id = event(f, "DYNAMIC", null, false);
        DeliveryService.Claim first = service.claim(null, "workerG");
        jdbc.update("UPDATE up_account SET default_all_group_id=NULL WHERE uid=?", f.uid);
        DeliveryService.Claim second = service.claim(null, "workerH");
        assertThat(second.role()).isEqualTo("UP");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery WHERE event_id=? AND role='ALL'",
                String.class, id)).isEqualTo("SENDING");
        service.result(id, "ALL", new DeliveryService.Result("workerG", first.leaseToken(), true, null));
        assertThat(group(id, "ALL")).isEqualTo(f.all);
        service.result(id, "UP", new DeliveryService.Result("workerH", second.leaseToken(), true, null));
    }

    private Fixture fixture() {
        String uid = Long.toString(System.nanoTime());
        String dynamicId = Long.toString(System.nanoTime() + 100);
        long ops = group("ops");
        long all = group("all");
        long own = group("own");
        long newAll = group("new-all");
        long newUp = group("new-up");
        jdbc.update("INSERT INTO up_account(uid,display_name,enabled,ops_group_id,default_all_group_id,default_up_group_id) VALUES (?,'测试UP',1,?,?,?)",
                uid, ops, all, own);
        jdbc.update("""
                INSERT INTO dynamic(dynamic_id,up_uid,content_text,published_at,comment_oid,comment_type,first_seen_at,last_seen_at)
                VALUES (?,?,'测试内容',UTC_TIMESTAMP(3),'1',11,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """, dynamicId, uid);
        return new Fixture(uid, dynamicId, ops, all, own, newAll, newUp);
    }

    private long group(String suffix) {
        return admin.createGroup("m7-" + suffix + "-" + System.nanoTime(),
                "https://open.feishu.cn/open-apis/bot/v2/hook/test-" + suffix).id();
    }

    private long event(Fixture f, String type, String rpid, boolean up) {
        String key = "m7:" + f.uid + ":" + type + ":" + rpid + ":" + System.nanoTime();
        jdbc.update("""
                INSERT INTO notification_event(dedupe_key,up_uid,dynamic_id,comment_rpid,event_type,is_up_comment,message_text,image_source_urls,ready_at)
                VALUES (?,?,?,?,?,?, '通知正文','[]',UTC_TIMESTAMP(3))
                """, key, f.uid, f.dynamicId, rpid, type, up);
        return jdbc.queryForObject("SELECT id FROM notification_event WHERE dedupe_key=?", Long.class, key);
    }

    private long group(long eventId, String role) {
        return jdbc.queryForObject("SELECT group_id FROM notification_delivery WHERE event_id=? AND role=?",
                Long.class, eventId, role);
    }

    private record Fixture(String uid, String dynamicId, long ops, long all, long own,
                           long newAll, long newUp) {}
}
