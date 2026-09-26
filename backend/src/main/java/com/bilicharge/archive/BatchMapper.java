package com.bilicharge.archive;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface BatchMapper {
    @Select("SELECT enabled FROM up_account WHERE uid=#{uid} FOR UPDATE")
    Integer lockUp(String uid);

    @Select("SELECT up_uid FROM dynamic WHERE dynamic_id=#{dynamicId}")
    String dynamicOwner(String dynamicId);

    @Insert("""
            INSERT INTO dynamic(dynamic_id,up_uid,title,content_text,published_at,comment_oid,
                                comment_type,first_seen_at,last_seen_at)
            VALUES(#{dynamicId},#{upUid},#{title},#{contentText},#{publishedAt},#{commentOid},
                   #{commentType},#{seenAt},#{seenAt})
            """)
    int insertDynamic(BatchRows.Dynamic row);

    @Update("""
            UPDATE dynamic SET title=#{title},content_text=#{contentText},published_at=#{publishedAt},
                comment_oid=#{commentOid},comment_type=#{commentType},last_seen_at=#{seenAt},
                source_unavailable_at=NULL
            WHERE dynamic_id=#{dynamicId} AND up_uid=#{upUid}
            """)
    int updateDynamic(BatchRows.Dynamic row);

    @Select("SELECT COUNT(*) FROM comment WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid}")
    int commentExists(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid);

    @Insert("""
            INSERT INTO comment(dynamic_id,rpid,root_rpid,parent_rpid,author_mid,author_name,
                                author_avatar_url,author_level,content_text,published_at,
                                like_count,reply_count,is_up,first_seen_at,last_seen_at)
            VALUES(#{dynamicId},#{rpid},#{rootRpid},#{parentRpid},#{authorMid},#{authorName},
                   #{authorAvatarUrl},#{authorLevel},#{contentText},#{publishedAt},
                   #{likeCount},#{replyCount},#{up},#{seenAt},#{seenAt})
            """)
    int insertComment(BatchRows.Comment row);

    @Update("""
            UPDATE comment SET root_rpid=#{rootRpid},parent_rpid=#{parentRpid},
                author_mid=#{authorMid},author_name=#{authorName},author_avatar_url=#{authorAvatarUrl},
                author_level=#{authorLevel},content_text=#{contentText},published_at=#{publishedAt},
                like_count=#{likeCount},reply_count=#{replyCount},is_up=#{up},
                last_seen_at=#{seenAt},source_unavailable_at=NULL
            WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid}
            """)
    int updateComment(BatchRows.Comment row);

    @Select("SELECT source_url FROM dynamic_image WHERE dynamic_id=#{dynamicId} AND position=#{position}")
    String dynamicImageSource(@Param("dynamicId") String dynamicId, @Param("position") int position);

    @Insert("INSERT INTO dynamic_image(dynamic_id,position,source_url) VALUES(#{dynamicId},#{position},#{sourceUrl})")
    int insertDynamicImage(@Param("dynamicId") String dynamicId, @Param("position") int position,
                           @Param("sourceUrl") String sourceUrl);

    @Update("""
            UPDATE dynamic_image SET source_url=#{sourceUrl},oss_key=NULL,upload_status='PENDING',
                retry_at=NULL,last_error=NULL,attempts=0 WHERE dynamic_id=#{dynamicId} AND position=#{position}
            """)
    int resetDynamicImage(@Param("dynamicId") String dynamicId, @Param("position") int position,
                          @Param("sourceUrl") String sourceUrl);

    @Delete("DELETE FROM dynamic_image WHERE dynamic_id=#{dynamicId} AND position>=#{count}")
    int trimDynamicImages(@Param("dynamicId") String dynamicId, @Param("count") int count);

    @Select("""
            SELECT source_url FROM comment_image
            WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid} AND position=#{position}
            """)
    String commentImageSource(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid,
                              @Param("position") int position);

    @Insert("""
            INSERT INTO comment_image(dynamic_id,rpid,position,source_url)
            VALUES(#{dynamicId},#{rpid},#{position},#{sourceUrl})
            """)
    int insertCommentImage(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid,
                           @Param("position") int position, @Param("sourceUrl") String sourceUrl);

    @Update("""
            UPDATE comment_image SET source_url=#{sourceUrl},oss_key=NULL,upload_status='PENDING',
                retry_at=NULL,last_error=NULL,attempts=0
            WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid} AND position=#{position}
            """)
    int resetCommentImage(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid,
                          @Param("position") int position, @Param("sourceUrl") String sourceUrl);

    @Delete("DELETE FROM comment_image WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid} AND position>=#{count}")
    int trimCommentImages(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid,
                          @Param("count") int count);

    @Insert("""
            INSERT INTO dynamic_scan_state(dynamic_id,state_json,last_complete_scan_at,
                                           last_full_scan_at,full_scan_retry_at)
            VALUES(#{dynamicId},#{stateJson},#{lastCompleteScanAt},#{lastFullScanAt},#{fullScanRetryAt})
            ON DUPLICATE KEY UPDATE state_json=VALUES(state_json),
                last_complete_scan_at=VALUES(last_complete_scan_at),
                last_full_scan_at=VALUES(last_full_scan_at),
                full_scan_retry_at=VALUES(full_scan_retry_at)
            """)
    int upsertScanState(BatchRows.ScanState row);

    @Select("SELECT baseline_completed_at FROM dynamic_scan_state WHERE dynamic_id=#{dynamicId}")
    LocalDateTime baselineCompletedAt(String dynamicId);

    @Select("SELECT COUNT(*) FROM dynamic_scan_state WHERE dynamic_id=#{dynamicId}")
    int scanStateExists(String dynamicId);

    @Update("""
            UPDATE dynamic_scan_state SET baseline_completed_at=#{completedAt}
            WHERE dynamic_id=#{dynamicId} AND baseline_completed_at IS NULL
            """)
    int completeBaseline(@Param("dynamicId") String dynamicId,
                         @Param("completedAt") LocalDateTime completedAt);

    @Insert("""
            INSERT INTO notification_event(dedupe_key,up_uid,dynamic_id,comment_rpid,event_type,
                                           is_up_comment,message_text,image_source_urls,ready_at)
            VALUES(#{dedupeKey},#{upUid},#{dynamicId},#{commentRpid},#{eventType},
                   #{upComment},#{messageText},#{imageSourceUrls},#{readyAt})
            ON DUPLICATE KEY UPDATE dedupe_key=dedupe_key
            """)
    int insertEvent(BatchRows.Event row);

    @Update("""
            UPDATE notification_event SET ready_at=#{readyAt}
            WHERE dynamic_id=#{dynamicId} AND ready_at IS NULL
            """)
    int releaseEvents(@Param("dynamicId") String dynamicId, @Param("readyAt") LocalDateTime readyAt);

    @Update("""
            UPDATE dynamic SET source_unavailable_at=COALESCE(source_unavailable_at,#{changedAt})
            WHERE dynamic_id=#{dynamicId}
            """)
    int markDynamicUnavailable(@Param("dynamicId") String dynamicId,
                               @Param("changedAt") LocalDateTime changedAt);

    @Update("UPDATE dynamic SET source_unavailable_at=NULL WHERE dynamic_id=#{dynamicId}")
    int markDynamicAvailable(String dynamicId);

    @Update("""
            UPDATE comment SET source_unavailable_at=COALESCE(source_unavailable_at,#{changedAt})
            WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid}
            """)
    int markCommentUnavailable(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid,
                               @Param("changedAt") LocalDateTime changedAt);

    @Update("UPDATE comment SET source_unavailable_at=NULL WHERE dynamic_id=#{dynamicId} AND rpid=#{rpid}")
    int markCommentAvailable(@Param("dynamicId") String dynamicId, @Param("rpid") String rpid);
}
