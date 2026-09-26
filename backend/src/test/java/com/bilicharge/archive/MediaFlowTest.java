package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MediaFlowTest extends MySqlTestBase {
    private static final String SOURCE = "https://i0.hdslb.com/bfs/m6-fixture.png";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    @Autowired JdbcTemplate jdbc;
    @Autowired MediaMapper mapper;
    @Autowired MediaJobRunner runner;
    @Autowired MockMvc mvc;
    @MockitoBean BiliImageDownloader downloader;
    @MockitoBean MediaStore store;

    @Test
    void retriesBothExternalFailuresKeepsTextAndSignsReadyDespiteSourceRemoval() throws Exception {
        Fixture fixture = create(false);
        try {
            when(store.configured()).thenReturn(true);
            when(downloader.fetch(SOURCE))
                    .thenThrow(new MediaDownloadException("SOURCE_HTTP_404", false))
                    .thenReturn(new BiliImageDownloader.Download(PNG, "image/png", "png"));
            doThrow(new IllegalStateException("secret must not be stored"))
                    .doNothing().when(store).putPrivate(anyString(), any(byte[].class), eq("image/png"));
            when(store.signedGet(anyString(), eq(Duration.ofMinutes(10))))
                    .thenReturn(URI.create("https://private.example.invalid/image?signature=first"),
                            URI.create("https://private.example.invalid/image?signature=second"));
            MockHttpSession session = login();
            String path = "/api/media/dynamics/" + fixture.dynamicId + "/0";

            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).session(session)).andExpect(status().isConflict());
            runner.runDue();
            assertThat(statusOf("dynamic_image", fixture.dynamicId)).isEqualTo("RETRY");
            assertThat(jdbc.queryForObject("SELECT last_error FROM dynamic_image WHERE dynamic_id=?", String.class,
                    fixture.dynamicId)).isEqualTo("SOURCE_HTTP_404");
            assertThat(jdbc.queryForObject("SELECT content_text FROM dynamic WHERE dynamic_id=?", String.class,
                    fixture.dynamicId)).isEqualTo("保留的文字");

            due(fixture.dynamicId);
            runner.runDue();
            assertThat(statusOf("dynamic_image", fixture.dynamicId)).isEqualTo("RETRY");
            assertThat(jdbc.queryForObject("SELECT last_error FROM dynamic_image WHERE dynamic_id=?", String.class,
                    fixture.dynamicId)).isEqualTo("MEDIA_STORE_ERROR");

            due(fixture.dynamicId);
            runner.runDue();
            assertThat(statusOf("dynamic_image", fixture.dynamicId)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT attempts FROM dynamic_image WHERE dynamic_id=?", Integer.class,
                    fixture.dynamicId)).isEqualTo(3);
            String key = jdbc.queryForObject("SELECT oss_key FROM dynamic_image WHERE dynamic_id=?", String.class,
                    fixture.dynamicId);
            assertThat(key).startsWith("bili-charge/dynamics/" + fixture.dynamicId + "/0/").endsWith(".png");
            verify(store, times(2)).putPrivate(eq(key), eq(PNG), eq("image/png"));

            jdbc.update("UPDATE dynamic SET source_unavailable_at=UTC_TIMESTAMP(3) WHERE dynamic_id=?", fixture.dynamicId);
            mvc.perform(get(path).session(session)).andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://private.example.invalid/image?signature=first"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(get(path).session(session)).andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://private.example.invalid/image?signature=second"));
            mvc.perform(get("/api/dynamics/" + fixture.dynamicId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .contains("保留的文字", "sourceUnavailable", "READY")
                            .doesNotContain(SOURCE));
        } finally { fixture.close(); }
    }

    @Test
    void definiteMissingCommentImageBecomesUnavailable() throws Exception {
        Fixture fixture = create(true);
        try {
            when(store.configured()).thenReturn(true);
            when(downloader.fetch(SOURCE)).thenThrow(new MediaDownloadException("SOURCE_GONE", true));
            runner.process(mapper.commentImage(fixture.dynamicId, fixture.rpid, 0), true);
            assertThat(jdbc.queryForObject("SELECT upload_status FROM comment_image WHERE dynamic_id=? AND rpid=?",
                    String.class, fixture.dynamicId, fixture.rpid)).isEqualTo("UNAVAILABLE");
            mvc.perform(get("/api/media/comments/" + fixture.dynamicId + "/" + fixture.rpid + "/0")
                    .session(login())).andExpect(status().isGone());
        } finally { fixture.close(); }
    }

    @Test
    void commentRedirectAndStaleUploadCannotReplaceChangedSource() throws Exception {
        Fixture fixture = create(true);
        try {
            when(store.configured()).thenReturn(true);
            when(store.signedGet(eq("fixture-private-key"), eq(Duration.ofMinutes(10))))
                    .thenReturn(URI.create("https://private.example.invalid/comment?signature=test"));
            jdbc.update("UPDATE comment_image SET upload_status='READY',oss_key='fixture-private-key' WHERE dynamic_id=?",
                    fixture.dynamicId);
            mvc.perform(get("/api/media/comments/" + fixture.dynamicId + "/" + fixture.rpid + "/0")
                    .session(login())).andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://private.example.invalid/comment?signature=test"));
            mvc.perform(get("/api/media/comments/" + fixture.dynamicId + "/" + fixture.rpid + "/1")
                    .session(login())).andExpect(status().isNotFound());

            MediaRows.Image old = mapper.dynamicImage(fixture.dynamicId, 0);
            assertThat(mapper.claimDynamic(old)).isEqualTo(1);
            jdbc.update("""
                    UPDATE dynamic_image SET source_url='https://i0.hdslb.com/bfs/replacement.png',
                        upload_status='PENDING',retry_at=NULL,attempts=0 WHERE dynamic_id=?
                    """, fixture.dynamicId);
            assertThat(mapper.readyDynamic(old, 1, "old-key")).isZero();
            assertThat(statusOf("dynamic_image", fixture.dynamicId)).isEqualTo("PENDING");
        } finally { fixture.close(); }
    }

    @Test
    void onlyExpectedCdnHostsCanBeDownloaded() throws Exception {
        assertThat(BiliImageDownloader.checkedSource(SOURCE).getHost()).isEqualTo("i0.hdslb.com");
        assertThat(BiliImageDownloader.checkedSource("http://i0.hdslb.com/bfs/old.jpg").getScheme())
                .isEqualTo("https");
        for (String invalid : new String[]{"http://127.0.0.1/a", "https://127.0.0.1/a",
                "https://i0.hdslb.com.evil.invalid/a", "https://i0.hdslb.com:8443/a"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> BiliImageDownloader.checkedSource(invalid))
                    .isInstanceOf(MediaDownloadException.class);
        }
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private void due(String id) {
        jdbc.update("UPDATE dynamic_image SET retry_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE dynamic_id=?", id);
    }

    private String statusOf(String table, String id) {
        String sql = table.equals("dynamic_image")
                ? "SELECT upload_status FROM dynamic_image WHERE dynamic_id=?"
                : "SELECT upload_status FROM comment_image WHERE dynamic_id=?";
        return jdbc.queryForObject(sql, String.class, id);
    }

    private Fixture create(boolean commentImage) {
        String id = "86" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        String uid = "85" + id.substring(2);
        String rpid = "84" + id.substring(2);
        String group = "m6-" + UUID.randomUUID().toString().substring(0, 10);
        jdbc.update("INSERT INTO feishu_group(name,webhook_ciphertext) VALUES (?,'fixture')", group);
        long groupId = jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?", Long.class, group);
        jdbc.update("INSERT INTO up_account(uid,display_name,enabled,ops_group_id,default_all_group_id) VALUES (?,'M6 UP',0,?,?)",
                uid, groupId, groupId);
        jdbc.update("""
                INSERT INTO dynamic(dynamic_id,up_uid,content_text,published_at,comment_oid,comment_type,first_seen_at,last_seen_at)
                VALUES (?,?,'保留的文字',UTC_TIMESTAMP(3),?,11,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """, id, uid, id);
        jdbc.update("""
                INSERT INTO comment(dynamic_id,rpid,author_mid,author_name,content_text,published_at,first_seen_at,last_seen_at)
                VALUES (?,?,'1','测试者','保留的评论',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """, id, rpid);
        jdbc.update("INSERT INTO dynamic_image(dynamic_id,position,source_url) VALUES (?,0,?)", id, SOURCE);
        if (commentImage)
            jdbc.update("INSERT INTO comment_image(dynamic_id,rpid,position,source_url) VALUES (?,?,0,?)", id, rpid, SOURCE);
        return new Fixture(id, uid, rpid, groupId, jdbc);
    }

    private record Fixture(String dynamicId, String uid, String rpid, long groupId, JdbcTemplate jdbc) {
        void close() {
            jdbc.update("DELETE FROM comment_image WHERE dynamic_id=?", dynamicId);
            jdbc.update("DELETE FROM dynamic_image WHERE dynamic_id=?", dynamicId);
            jdbc.update("DELETE FROM comment WHERE dynamic_id=?", dynamicId);
            jdbc.update("DELETE FROM dynamic WHERE dynamic_id=?", dynamicId);
            jdbc.update("DELETE FROM up_account WHERE uid=?", uid);
            jdbc.update("DELETE FROM feishu_group WHERE id=?", groupId);
        }
    }
}
