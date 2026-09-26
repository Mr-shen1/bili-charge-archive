package com.bilicharge.archive;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface DeliveryMapper {
    @Select("SELECT id FROM delivery_claim_lock WHERE id=1 FOR UPDATE")
    int lockClaims();

    @Select("""
            SELECT e.id,e.up_uid,e.dynamic_id,e.comment_rpid,e.event_type,e.is_up_comment,
                   e.message_text,CAST(e.image_source_urls AS CHAR) AS image_source_urls
            FROM notification_event e JOIN up_account u ON u.uid=e.up_uid
            WHERE e.ready_at IS NOT NULL AND e.completed_at IS NULL
              AND e.canceled_at IS NULL AND u.enabled=1
            ORDER BY e.id
            """)
    List<DeliveryRows.Event> readyEvents();

    @Select("""
            SELECT id,up_uid,dynamic_id,comment_rpid,event_type,is_up_comment,message_text,
                   CAST(image_source_urls AS CHAR) AS image_source_urls
            FROM notification_event WHERE id=#{id}
            """)
    DeliveryRows.Event event(long id);

    @Select("""
            SELECT event_id,role,group_id,group_name_snapshot,status,attempts,
                   next_retry_at,lease_owner,lease_until,sent_at
            FROM notification_delivery WHERE event_id=#{eventId} AND role=#{role}
            """)
    DeliveryRows.Target target(@Param("eventId") long eventId, @Param("role") String role);

    @Insert("""
            INSERT INTO notification_delivery(event_id,role,group_id,group_name_snapshot)
            VALUES(#{eventId},#{role},#{groupId},#{groupName})
            ON DUPLICATE KEY UPDATE event_id=event_id
            """)
    int insertTarget(@Param("eventId") long eventId, @Param("role") String role,
                     @Param("groupId") Long groupId, @Param("groupName") String groupName);

    @Update("""
            UPDATE notification_delivery SET group_id=#{groupId},group_name_snapshot=#{groupName},
                   status='PENDING',next_retry_at=NULL,lease_owner=NULL,lease_until=NULL,last_error=NULL
            WHERE event_id=#{eventId} AND role=#{role} AND status<>'SENT'
            """)
    int retarget(@Param("eventId") long eventId, @Param("role") String role,
                 @Param("groupId") Long groupId, @Param("groupName") String groupName);

    @Update("""
            UPDATE notification_delivery SET status='SKIPPED',group_id=NULL,
                   next_retry_at=NULL,lease_owner=NULL,lease_until=NULL
            WHERE event_id=#{eventId} AND role=#{role} AND status<>'SENT'
            """)
    int skip(@Param("eventId") long eventId, @Param("role") String role);

    @Update("""
            UPDATE notification_delivery SET status='SENDING',attempts=attempts+1,
                   lease_owner=#{token},lease_until=#{until},next_retry_at=NULL
            WHERE event_id=#{eventId} AND role=#{role} AND group_id=#{groupId}
              AND ((status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=#{now}))
                   OR (status='SENDING' AND lease_until<=#{now}))
            """)
    int claim(@Param("eventId") long eventId, @Param("role") String role,
              @Param("groupId") long groupId, @Param("token") String token,
              @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE notification_delivery SET status='SENT',sent_at=#{now},
                   lease_owner=NULL,lease_until=NULL,next_retry_at=NULL,last_error=NULL
            WHERE event_id=#{eventId} AND role=#{role} AND status='SENDING'
              AND lease_owner=#{token} AND lease_until>#{now}
            """)
    int sent(@Param("eventId") long eventId, @Param("role") String role,
             @Param("token") String token, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE notification_delivery SET status='PENDING',next_retry_at=#{retryAt},
                   lease_owner=NULL,lease_until=NULL,last_error=#{error}
            WHERE event_id=#{eventId} AND role=#{role} AND status='SENDING'
              AND lease_owner=#{token} AND lease_until>#{now}
            """)
    int failed(@Param("eventId") long eventId, @Param("role") String role,
               @Param("token") String token, @Param("now") LocalDateTime now,
               @Param("retryAt") LocalDateTime retryAt, @Param("error") String error);

    @Update("""
            UPDATE notification_event SET completed_at=#{now}
            WHERE id=#{eventId} AND completed_at IS NULL AND canceled_at IS NULL
            """)
    int complete(@Param("eventId") long eventId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO notification_event(dedupe_key,up_uid,event_type,message_text,ready_at)
            VALUES(#{key},#{uid},#{type},#{message},#{now})
            ON DUPLICATE KEY UPDATE dedupe_key=dedupe_key
            """)
    int insertOps(@Param("key") String key, @Param("uid") String uid,
                  @Param("type") String type, @Param("message") String message,
                  @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM notification_event WHERE completed_at<#{before}
            """)
    int deleteCompletedBefore(LocalDateTime before);

    @Select("""
            <script>
            SELECT d.event_id,d.role,d.group_id,d.group_name_snapshot,d.status,d.attempts,
                   d.next_retry_at,d.sent_at,d.last_error,e.up_uid,e.event_type
            FROM notification_delivery d JOIN notification_event e ON e.id=d.event_id
            WHERE (#{uid} IS NULL OR e.up_uid=#{uid})
              AND (#{status} IS NULL OR d.status=#{status})
              AND e.canceled_at IS NULL
              AND (e.completed_at IS NULL OR e.completed_at>=#{since})
            <if test='afterId != null'>
              AND (d.event_id ${operator} #{afterId}
                   OR (d.event_id=#{afterId} AND d.role ${operator} #{afterRole}))
            </if>
            ORDER BY d.event_id ${order},d.role ${order} LIMIT 21
            </script>
            """)
    List<java.util.Map<String, Object>> recent(@Param("uid") String uid,
            @Param("status") String status, @Param("since") LocalDateTime since,
            @Param("afterId") Long afterId, @Param("afterRole") String afterRole,
            @Param("operator") String operator, @Param("order") String order);
}
