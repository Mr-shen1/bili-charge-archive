package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class MySqlConstraintsTest extends MySqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired DatabaseProbeMapper mapper;

    @Test
    void migrationIsIdempotentAndMapperWorks() {
        assertThat(mapper.ping()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('feishu_group','up_account','dynamic_route','dynamic','dynamic_image','comment','comment_image','dynamic_scan_state','notification_event','notification_delivery')", Integer.class)).isEqualTo(10);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name='dynamic' AND column_name='dynamic_id'", String.class)).isEqualTo("varchar");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name='dynamic_scan_state' AND column_name='baseline_completed_at'", Integer.class)).isEqualTo(1);
    }

    @Test
    @Transactional
    void upAndRouteConstraintsAreEnforced() {
        long groupA = group();
        long groupB = group();
        assertThatThrownBy(() -> up("empty", groupA, null, null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> up("duplicate", groupA, groupA, groupA)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> up("bad-fk", 999999999L, groupA, null)).isInstanceOf(DataAccessException.class);
        up("valid", groupA, groupB, null);
        assertThatThrownBy(() -> route("empty", "valid", null, null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> route("duplicate", "valid", groupA, groupA)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> route("bad-fk", "valid", 999999999L, null)).isInstanceOf(DataAccessException.class);
        route("valid-route", "valid", groupA, groupB);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM feishu_group WHERE id=?", groupA)).isInstanceOf(DataAccessException.class);
    }

    private long group() {
        String name = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO feishu_group(name,webhook_ciphertext) VALUES (?,?)", name, "test-ciphertext");
        return jdbc.queryForObject("SELECT id FROM feishu_group WHERE name=?", Long.class, name);
    }

    private void up(String uid, long ops, Long all, Long up) {
        jdbc.update("INSERT INTO up_account(uid,display_name,ops_group_id,default_all_group_id,default_up_group_id) VALUES (?,?,?,?,?)", uid, uid, ops, all, up);
    }

    private void route(String id, String uid, Long all, Long up) {
        jdbc.update("INSERT INTO dynamic_route(dynamic_id,up_uid,all_group_id,up_group_id) VALUES (?,?,?,?)", id, uid, all, up);
    }
}
