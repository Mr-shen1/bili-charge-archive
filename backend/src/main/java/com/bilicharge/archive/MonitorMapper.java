package com.bilicharge.archive;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface MonitorMapper {
    @Select("SELECT uid FROM up_account WHERE enabled=1 ORDER BY uid")
    List<String> enabledUids();

    @Select("""
            SELECT s.dynamic_id AS dynamicId, CAST(s.state_json AS CHAR) AS stateJson,
                   s.last_complete_scan_at AS lastCompleteScanAt,
                   s.last_full_scan_at AS lastFullScanAt,
                   s.full_scan_retry_at AS fullScanRetryAt,
                   s.baseline_completed_at AS baselineCompletedAt
            FROM dynamic_scan_state s JOIN dynamic d ON d.dynamic_id=s.dynamic_id
            WHERE d.up_uid=#{uid}
            """)
    List<Scan> scans(String uid);

    @Update("UPDATE up_account SET worker_heartbeat_at=#{now} WHERE uid=#{uid} AND enabled=1")
    int heartbeat(@Param("uid") String uid, @Param("now") LocalDateTime now);

    @Update("UPDATE up_account SET worker_heartbeat_at=#{now},last_scan_started_at=#{now} WHERE uid=#{uid} AND enabled=1")
    int started(@Param("uid") String uid, @Param("now") LocalDateTime now);

    @Update("UPDATE up_account SET worker_heartbeat_at=#{now},last_scan_succeeded_at=#{now},last_scan_error=NULL WHERE uid=#{uid} AND enabled=1")
    int succeeded(@Param("uid") String uid, @Param("now") LocalDateTime now);

    @Update("UPDATE up_account SET worker_heartbeat_at=#{now},last_scan_error_at=#{now},last_scan_error=#{message} WHERE uid=#{uid} AND enabled=1")
    int errored(@Param("uid") String uid, @Param("now") LocalDateTime now,
                @Param("message") String message);

    final class Scan {
        public String dynamicId;
        public String stateJson;
        public LocalDateTime lastCompleteScanAt;
        public LocalDateTime lastFullScanAt;
        public LocalDateTime fullScanRetryAt;
        public LocalDateTime baselineCompletedAt;
    }
}
