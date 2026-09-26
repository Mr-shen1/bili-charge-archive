package com.bilicharge.archive;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface MediaMapper {
    @Select("""
            SELECT dynamic_id,position,source_url,oss_key,upload_status,attempts
            FROM dynamic_image
            WHERE upload_status IN ('PENDING','RETRY') AND (retry_at IS NULL OR retry_at<=UTC_TIMESTAMP(3))
            ORDER BY COALESCE(retry_at,'1970-01-01'),dynamic_id,position LIMIT 10
            """)
    List<MediaRows.Image> dueDynamics();

    @Select("""
            SELECT dynamic_id,rpid,position,source_url,oss_key,upload_status,attempts
            FROM comment_image
            WHERE upload_status IN ('PENDING','RETRY') AND (retry_at IS NULL OR retry_at<=UTC_TIMESTAMP(3))
            ORDER BY COALESCE(retry_at,'1970-01-01'),dynamic_id,rpid,position LIMIT 10
            """)
    List<MediaRows.Image> dueComments();

    @Select("SELECT dynamic_id,position,source_url,oss_key,upload_status,attempts FROM dynamic_image WHERE dynamic_id=#{id} AND position=#{position}")
    MediaRows.Image dynamicImage(@Param("id") String id, @Param("position") int position);

    @Select("SELECT dynamic_id,rpid,position,source_url,oss_key,upload_status,attempts FROM comment_image WHERE dynamic_id=#{id} AND rpid=#{rpid} AND position=#{position}")
    MediaRows.Image commentImage(@Param("id") String id, @Param("rpid") String rpid,
                                  @Param("position") int position);

    @Update("""
            UPDATE dynamic_image SET upload_status='RETRY',retry_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE),
                attempts=attempts+1,last_error=NULL
            WHERE dynamic_id=#{job.dynamicId} AND position=#{job.position} AND source_url=#{job.sourceUrl}
              AND upload_status IN ('PENDING','RETRY') AND (retry_at IS NULL OR retry_at<=UTC_TIMESTAMP(3))
            """)
    int claimDynamic(@Param("job") MediaRows.Image job);

    @Update("""
            UPDATE comment_image SET upload_status='RETRY',retry_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE),
                attempts=attempts+1,last_error=NULL
            WHERE dynamic_id=#{job.dynamicId} AND rpid=#{job.rpid} AND position=#{job.position}
              AND source_url=#{job.sourceUrl} AND upload_status IN ('PENDING','RETRY')
              AND (retry_at IS NULL OR retry_at<=UTC_TIMESTAMP(3))
            """)
    int claimComment(@Param("job") MediaRows.Image job);

    @Update("""
            UPDATE dynamic_image SET upload_status='READY',oss_key=#{key},retry_at=NULL,last_error=NULL
            WHERE dynamic_id=#{job.dynamicId} AND position=#{job.position} AND source_url=#{job.sourceUrl}
              AND attempts=#{attempts} AND upload_status='RETRY'
            """)
    int readyDynamic(@Param("job") MediaRows.Image job, @Param("attempts") int attempts,
                     @Param("key") String key);

    @Update("""
            UPDATE comment_image SET upload_status='READY',oss_key=#{key},retry_at=NULL,last_error=NULL
            WHERE dynamic_id=#{job.dynamicId} AND rpid=#{job.rpid} AND position=#{job.position}
              AND source_url=#{job.sourceUrl} AND attempts=#{attempts} AND upload_status='RETRY'
            """)
    int readyComment(@Param("job") MediaRows.Image job, @Param("attempts") int attempts,
                     @Param("key") String key);

    @Update("""
            UPDATE dynamic_image SET upload_status=#{status},retry_at=#{retryAt},last_error=#{error}
            WHERE dynamic_id=#{job.dynamicId} AND position=#{job.position} AND source_url=#{job.sourceUrl}
              AND attempts=#{attempts} AND upload_status='RETRY'
            """)
    int failDynamic(@Param("job") MediaRows.Image job, @Param("attempts") int attempts,
                    @Param("status") String status, @Param("retryAt") LocalDateTime retryAt,
                    @Param("error") String error);

    @Update("""
            UPDATE comment_image SET upload_status=#{status},retry_at=#{retryAt},last_error=#{error}
            WHERE dynamic_id=#{job.dynamicId} AND rpid=#{job.rpid} AND position=#{job.position}
              AND source_url=#{job.sourceUrl} AND attempts=#{attempts} AND upload_status='RETRY'
            """)
    int failComment(@Param("job") MediaRows.Image job, @Param("attempts") int attempts,
                    @Param("status") String status, @Param("retryAt") LocalDateTime retryAt,
                    @Param("error") String error);
}
