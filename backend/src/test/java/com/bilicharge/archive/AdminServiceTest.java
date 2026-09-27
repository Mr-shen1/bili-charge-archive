package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AdminServiceTest extends MySqlTestBase {
    @Autowired AdminService service;
    @Autowired AdminMapper mapper;
    @Autowired WebhookCipher cipher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BiliPreviewClient bili;

    private static final String UID = "550494308";
    private static final String DYNAMIC_ID = "1251460007328743432";
    private static final String WEBHOOK = "https://open.feishu.cn/open-apis/bot/v2/hook/test-only-123";

    @Test
    void oneGroupCanBeReusedByDifferentUps() {
        String otherUid = "550494309";
        long shared = group("shared");
        long firstOps = group("first-ops");
        long secondOps = group("second-ops");
        when(bili.previewUser(UID)).thenReturn(new BiliPreviewClient.User(UID, "测试 UP 甲", ""));
        when(bili.previewUser(otherUid)).thenReturn(new BiliPreviewClient.User(otherUid, "测试 UP 乙", ""));

        service.createUp(UID, firstOps, shared, null);
        service.createUp(otherUid, secondOps, shared, null);

        assertThat(service.ups()).extracting(AdminService.UpView::defaultAllGroupId)
                .contains(shared, shared);
        assertThat(service.groups().stream().filter(group -> group.id().equals(shared))
                .findFirst().orElseThrow().references())
                .anyMatch(ref -> ref.contains("UP " + UID))
                .anyMatch(ref -> ref.contains("UP " + otherUid));
    }

    @Test
    void groupsAreEncryptedReusableAndProtectedByAllReferences() throws Exception {
        long ops = group("ops");
        long all = group("all");
        long own = group("own");
        assertThat(mapper.group(ops).webhookCiphertext).startsWith("v1:").doesNotContain(WEBHOOK);
        assertThat(cipher.decrypt(mapper.group(ops).webhookCiphertext)).isEqualTo(WEBHOOK);
        assertThat(json.writeValueAsString(service.groups())).doesNotContain(WEBHOOK, "webhookCiphertext");

        when(bili.previewUser(UID)).thenReturn(new BiliPreviewClient.User(UID, "测试 UP", ""));
        service.createUp(UID, ops, all, own);
        assertCode(() -> service.deleteGroup(ops), "GROUP_IN_USE");
        assertCode(() -> service.deleteGroup(all), "GROUP_IN_USE");
        assertCode(() -> service.deleteGroup(own), "GROUP_IN_USE");
        when(bili.previewDynamic(UID, DYNAMIC_ID)).thenReturn(dynamic());
        service.saveRoute(UID, DYNAMIC_ID, own, null);
        assertThat(service.routes(UID)).hasSize(1);
        assertThat(service.groups().stream().filter(g -> g.id().equals(own)).findFirst().orElseThrow().references())
                .anyMatch(ref -> ref.contains("动态 " + DYNAMIC_ID));
        service.deleteRoute(UID, DYNAMIC_ID);
        assertThat(service.routes(UID)).isEmpty();
    }

    @Test
    void rejectsInvalidDefaultAndDedicatedRoutes() {
        long ops = group("ops");
        long all = group("all");
        assertCode(() -> service.createUp(UID, null, all, null), "INVALID_GROUP");
        assertCode(() -> service.createUp(UID, ops, null, null), "INVALID_ROUTE");
        assertCode(() -> service.createUp(UID, ops, all, all), "INVALID_ROUTE");
        assertCode(() -> service.createUp(UID, ops, 99999999L, null), "INVALID_GROUP");
        when(bili.previewUser(UID)).thenReturn(new BiliPreviewClient.User(UID, "测试 UP", ""));
        assertThat(service.createUp(UID, ops, all, null).enabled()).isTrue();
        assertCode(() -> service.saveRoute(UID, DYNAMIC_ID, null, null), "INVALID_ROUTE");
        assertCode(() -> service.saveRoute(UID, DYNAMIC_ID, all, all), "INVALID_ROUTE");
        assertCode(() -> service.saveRoute(UID, DYNAMIC_ID, 99999999L, null), "INVALID_GROUP");
        assertThat(service.status(UID).pendingCount()).isZero();
    }

    @Test
    void groupEditAndUpEnableStatePersist() throws Exception {
        long temporary = group("temporary");
        String replacement = "https://open.feishu.cn/open-apis/bot/v2/hook/replacement-only";
        service.updateGroup(temporary, "m2-renamed-" + System.nanoTime(), replacement);
        assertThat(cipher.decrypt(mapper.group(temporary).webhookCiphertext)).isEqualTo(replacement);
        service.deleteGroup(temporary);
        assertThat(mapper.group(temporary)).isNull();

        long ops = group("ops");
        long all = group("all");
        when(bili.previewUser(UID)).thenReturn(new BiliPreviewClient.User(UID, "测试 UP", ""));
        service.createUp(UID, ops, all, null);
        assertThat(service.updateUp(UID, json.readTree("{\"enabled\":false}")).enabled()).isFalse();
        assertThat(service.status(UID).enabled()).isFalse();
        assertThat(service.updateUp(UID, json.readTree("{\"enabled\":true}")).enabled()).isTrue();
    }

    @Test
    void savingRouteRechecksSourceAndUpdateChangesFutureRoutingOnly() {
        long ops = group("ops");
        long all = group("all");
        long own = group("own");
        when(bili.previewUser(UID)).thenReturn(new BiliPreviewClient.User(UID, "测试 UP", ""));
        service.createUp(UID, ops, all, null);
        when(bili.previewDynamic(UID, DYNAMIC_ID)).thenReturn(dynamic());
        service.saveRoute(UID, DYNAMIC_ID, all, null);
        service.saveRoute(UID, DYNAMIC_ID, null, own);
        assertThat(service.routes(UID)).extracting(AdminService.RouteView::upGroupId).containsExactly(own);
        assertThat(service.routes(UID)).extracting(AdminService.RouteView::allGroupId).containsExactly((Long) null);
    }

    @Test
    void savingFirstDedicatedRouteCancelsOtherQueuedEvents() {
        long group = group("route-scope");
        when(bili.previewUser(UID)).thenReturn(new BiliPreviewClient.User(UID, "测试 UP", ""));
        service.createUp(UID, group, group, null);
        String historical = DYNAMIC_ID + "1";
        jdbc.update("""
                INSERT INTO dynamic(dynamic_id,up_uid,content_text,published_at,comment_oid,
                                    comment_type,first_seen_at,last_seen_at)
                VALUES(?,?, '历史',UTC_TIMESTAMP(3),'123',11,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """, historical, UID);
        jdbc.update("""
                INSERT INTO notification_event(dedupe_key,up_uid,dynamic_id,event_type,message_text,ready_at)
                VALUES(?,?,?,'DYNAMIC','历史通知',UTC_TIMESTAMP(3))
                """, "dynamic:" + historical, UID, historical);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE up_uid=?",
                Long.class, UID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE up_uid=? AND completed_at IS NULL AND canceled_at IS NULL",
                Long.class, UID)).isEqualTo(1);
        when(bili.previewDynamic(UID, DYNAMIC_ID)).thenReturn(dynamic());
        service.saveRoute(UID, DYNAMIC_ID, group, null);
        assertThat(service.status(UID).pendingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT canceled_at IS NOT NULL FROM notification_event WHERE dynamic_id=?",
                Boolean.class, historical)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dynamic WHERE dynamic_id=?",
                Integer.class, historical)).isEqualTo(1);
    }

    @Test
    void deletingOneDedicatedRouteCancelsItsDynamicAndCommentNotifications() {
        long group = group("delete-scope");
        when(bili.previewUser(UID)).thenReturn(new BiliPreviewClient.User(UID, "测试 UP", ""));
        service.createUp(UID, group, group, null);
        when(bili.previewDynamic(UID, DYNAMIC_ID)).thenReturn(dynamic());
        service.saveRoute(UID, DYNAMIC_ID, group, null);
        jdbc.update("""
                INSERT INTO dynamic(dynamic_id,up_uid,content_text,published_at,comment_oid,
                                    comment_type,first_seen_at,last_seen_at)
                VALUES(?,?, '历史',UTC_TIMESTAMP(3),'123',11,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """, DYNAMIC_ID, UID);
        jdbc.update("""
                INSERT INTO notification_event(dedupe_key,up_uid,dynamic_id,event_type,message_text,ready_at)
                VALUES(?,?,?,'DYNAMIC','历史动态',UTC_TIMESTAMP(3))
                """, "delete:dynamic:" + DYNAMIC_ID, UID, DYNAMIC_ID);
        jdbc.update("""
                INSERT INTO notification_event(dedupe_key,up_uid,dynamic_id,comment_rpid,event_type,message_text,ready_at)
                VALUES(?,?,?,'123','COMMENT','历史评论',UTC_TIMESTAMP(3))
                """, "delete:comment:" + DYNAMIC_ID, UID, DYNAMIC_ID);
        jdbc.update("INSERT INTO dynamic_route(dynamic_id,up_uid,all_group_id) VALUES(?,?,?)",
                DYNAMIC_ID + "1", UID, group);

        assertThat(service.status(UID).pendingCount()).isEqualTo(2);
        service.deleteRoute(UID, DYNAMIC_ID);

        assertThat(service.status(UID).pendingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE dynamic_id=? AND canceled_at IS NOT NULL",
                Integer.class, DYNAMIC_ID)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dynamic WHERE dynamic_id=?",
                Integer.class, DYNAMIC_ID)).isEqualTo(1);
    }

    private BiliPreviewClient.Dynamic dynamic() {
        return new BiliPreviewClient.Dynamic(DYNAMIC_ID, UID, "测试 UP", "测试动态",
                "DYNAMIC_TYPE_WORD", "MAJOR_TYPE_OPUS", "12345", 17);
    }

    private long group(String suffix) {
        return service.createGroup("m2-" + suffix + "-" + System.nanoTime(), WEBHOOK).id();
    }

    private void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run).isInstanceOf(AdminException.class)
                .satisfies(error -> assertThat(((AdminException) error).code()).isEqualTo(code));
    }
}
