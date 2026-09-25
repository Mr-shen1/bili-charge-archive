package com.bilicharge.archive;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AdminMapper {
    @Select("SELECT id, name, webhook_ciphertext FROM feishu_group ORDER BY id")
    List<AdminRows.Group> groups();

    @Select("SELECT id, name, webhook_ciphertext FROM feishu_group WHERE id=#{id}")
    AdminRows.Group group(long id);

    @Insert("INSERT INTO feishu_group(name, webhook_ciphertext) VALUES (#{name}, #{webhookCiphertext})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGroup(AdminRows.Group group);

    @Update("UPDATE feishu_group SET name=#{name}, webhook_ciphertext=#{webhookCiphertext} WHERE id=#{id}")
    int updateGroup(AdminRows.Group group);

    @Delete("DELETE FROM feishu_group WHERE id=#{id}")
    int deleteGroup(long id);

    @Select("""
            SELECT CONCAT('UP ', uid, ' · 运维群') FROM up_account WHERE ops_group_id=#{id}
            UNION ALL SELECT CONCAT('UP ', uid, ' · 全部评论群') FROM up_account WHERE default_all_group_id=#{id}
            UNION ALL SELECT CONCAT('UP ', uid, ' · UP 群') FROM up_account WHERE default_up_group_id=#{id}
            UNION ALL SELECT CONCAT('动态 ', dynamic_id, ' · 全部评论群') FROM dynamic_route WHERE all_group_id=#{id}
            UNION ALL SELECT CONCAT('动态 ', dynamic_id, ' · UP 群') FROM dynamic_route WHERE up_group_id=#{id}
            """)
    List<String> groupReferences(long id);

    @Select("""
            SELECT uid, display_name, avatar_url, enabled, ops_group_id, default_all_group_id,
                   default_up_group_id, worker_heartbeat_at, last_scan_started_at,
                   last_scan_succeeded_at, last_scan_error_at, last_scan_error
            FROM up_account ORDER BY uid
            """)
    List<AdminRows.Up> ups();

    @Select("""
            SELECT uid, display_name, avatar_url, enabled, ops_group_id, default_all_group_id,
                   default_up_group_id, worker_heartbeat_at, last_scan_started_at,
                   last_scan_succeeded_at, last_scan_error_at, last_scan_error
            FROM up_account WHERE uid=#{uid}
            """)
    AdminRows.Up up(String uid);

    @Insert("""
            INSERT INTO up_account(uid, display_name, avatar_url, enabled, ops_group_id,
                                   default_all_group_id, default_up_group_id)
            VALUES (#{uid}, #{displayName}, #{avatarUrl}, #{enabled}, #{opsGroupId},
                    #{defaultAllGroupId}, #{defaultUpGroupId})
            """)
    int insertUp(AdminRows.Up up);

    @Update("""
            UPDATE up_account SET enabled=#{enabled}, ops_group_id=#{opsGroupId},
                default_all_group_id=#{defaultAllGroupId}, default_up_group_id=#{defaultUpGroupId}
            WHERE uid=#{uid}
            """)
    int updateUp(AdminRows.Up up);

    @Select("SELECT COUNT(*) FROM notification_event WHERE up_uid=#{uid} AND completed_at IS NULL")
    long pendingCount(String uid);

    @Select("""
            SELECT COUNT(*) FROM notification_delivery d
            JOIN notification_event e ON e.id=d.event_id
            WHERE e.up_uid=#{uid} AND d.status IN ('PENDING','SENDING') AND d.attempts>0
            """)
    long failedCount(String uid);

    @Select("SELECT dynamic_id, up_uid, all_group_id, up_group_id FROM dynamic_route WHERE up_uid=#{uid} ORDER BY dynamic_id")
    List<AdminRows.Route> routes(String uid);

    @Select("SELECT dynamic_id, up_uid, all_group_id, up_group_id FROM dynamic_route WHERE dynamic_id=#{dynamicId}")
    AdminRows.Route route(String dynamicId);

    @Insert("INSERT INTO dynamic_route(dynamic_id,up_uid,all_group_id,up_group_id) VALUES (#{dynamicId},#{upUid},#{allGroupId},#{upGroupId})")
    int insertRoute(AdminRows.Route route);

    @Update("UPDATE dynamic_route SET all_group_id=#{allGroupId}, up_group_id=#{upGroupId} WHERE dynamic_id=#{dynamicId} AND up_uid=#{upUid}")
    int updateRoute(AdminRows.Route route);

    @Delete("DELETE FROM dynamic_route WHERE dynamic_id=#{dynamicId} AND up_uid=#{uid}")
    int deleteRoute(@Param("uid") String uid, @Param("dynamicId") String dynamicId);
}
