package com.bilicharge.archive;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface ContentMapper {
    @Select("SELECT uid,display_name,avatar_url,enabled FROM up_account ORDER BY uid")
    List<ContentRows.Up> ups();

    @Select("""
            <script>
            SELECT ranked.* FROM (
              SELECT d.dynamic_id,d.up_uid,u.display_name AS up_name,u.avatar_url AS up_avatar_url,
                     u.enabled AS up_enabled,d.title,d.content_text AS text,d.published_at,
                     d.source_unavailable_at IS NOT NULL AS source_unavailable,
                     (SELECT COUNT(*) FROM comment c WHERE c.dynamic_id=d.dynamic_id) AS stored_comment_count,
                     CASE WHEN EXISTS (SELECT 1 FROM dynamic_route r WHERE r.up_uid=d.up_uid)
                               AND NOT EXISTS (SELECT 1 FROM dynamic_route r WHERE r.dynamic_id=d.dynamic_id)
                          THEN 1 ELSE 0 END AS history_rank
              FROM dynamic d JOIN up_account u ON u.uid=d.up_uid
            ) ranked
            WHERE 1=1
            <if test='upUid != null'>AND ranked.up_uid=#{upUid}</if>
            <if test='at != null'>
              AND (ranked.history_rank ${rankOperator} #{rank}
                   OR (ranked.history_rank=#{rank}
                       AND (ranked.published_at ${operator} #{at}
                            OR (ranked.published_at=#{at} AND (LENGTH(ranked.dynamic_id),ranked.dynamic_id) ${operator} (LENGTH(#{id}),#{id})))))
            </if>
            ORDER BY ranked.history_rank ${rankOrder},ranked.published_at ${order},
                     LENGTH(ranked.dynamic_id) ${order},ranked.dynamic_id ${order}
            LIMIT 21
            </script>
            """)
    List<ContentRows.Dynamic> dynamics(@Param("upUid") String upUid, @Param("at") LocalDateTime at,
                                       @Param("id") String id, @Param("rank") Integer rank,
                                       @Param("operator") String operator, @Param("order") String order,
                                       @Param("rankOperator") String rankOperator,
                                       @Param("rankOrder") String rankOrder);

    @Select("""
            SELECT d.dynamic_id,d.up_uid,u.display_name AS up_name,u.avatar_url AS up_avatar_url,
                   u.enabled AS up_enabled,d.title,d.content_text AS text,d.published_at,
                   d.source_unavailable_at IS NOT NULL AS source_unavailable,
                   (SELECT COUNT(*) FROM comment c WHERE c.dynamic_id=d.dynamic_id) AS stored_comment_count,
                   CASE WHEN EXISTS (SELECT 1 FROM dynamic_route r WHERE r.up_uid=d.up_uid)
                             AND NOT EXISTS (SELECT 1 FROM dynamic_route r WHERE r.dynamic_id=d.dynamic_id)
                        THEN 1 ELSE 0 END AS history_rank
            FROM dynamic d JOIN up_account u ON u.uid=d.up_uid WHERE d.dynamic_id=#{id}
            """)
    ContentRows.Dynamic dynamic(String id);

    @Select("""
            <script>
            SELECT c.dynamic_id,c.rpid,c.root_rpid,c.parent_rpid,c.author_mid,c.author_name,
                   c.author_avatar_url,c.author_level,c.content_text AS text,c.published_at,
                   c.like_count,c.reply_count,c.is_up,c.source_unavailable_at IS NOT NULL AS source_unavailable,
                   (SELECT COUNT(*) FROM comment child WHERE child.dynamic_id=c.dynamic_id
                    AND child.root_rpid=c.rpid) AS stored_reply_count
            FROM comment c WHERE c.dynamic_id=#{dynamicId}
            <choose>
              <when test='rootRpid == null'>AND c.root_rpid IS NULL</when>
              <otherwise>AND c.root_rpid=#{rootRpid}</otherwise>
            </choose>
            <if test='at != null'>
              AND (c.published_at ${operator} #{at}
                   OR (c.published_at=#{at} AND (LENGTH(c.rpid),c.rpid) ${operator} (LENGTH(#{id}),#{id})))
            </if>
            ORDER BY c.published_at ${order},LENGTH(c.rpid) ${order},c.rpid ${order}
            LIMIT 21
            </script>
            """)
    List<ContentRows.Comment> comments(@Param("dynamicId") String dynamicId,
            @Param("rootRpid") String rootRpid, @Param("at") LocalDateTime at,
            @Param("id") String id, @Param("operator") String operator, @Param("order") String order);

    @Select("SELECT 1 FROM comment WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid} AND root_rpid IS NULL")
    Integer rootExists(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid);

    @Select("SELECT position,upload_status AS status FROM dynamic_image WHERE dynamic_id=#{id} ORDER BY position")
    List<ContentRows.Image> dynamicImages(String id);

    @Select("SELECT position,upload_status AS status FROM comment_image WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid} ORDER BY position")
    List<ContentRows.Image> commentImages(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid);
}
