package com.bilicharge.archive;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OpsEventService {
    private final MonitorMapper monitor;
    private final AdminMapper admin;
    private final DeliveryMapper delivery;

    OpsEventService(MonitorMapper monitor, AdminMapper admin, DeliveryMapper delivery) {
        this.monitor = monitor;
        this.admin = admin;
        this.delivery = delivery;
    }

    @Transactional
    void status(String uid, String kind, String message) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (kind == null) throw invalid();
        String safe = safeMessage(message);
        int changed = switch (kind) {
            case "HEARTBEAT" -> monitor.heartbeat(uid, now);
            case "STARTED" -> monitor.started(uid, now);
            case "SUCCEEDED" -> monitor.succeeded(uid, now);
            case "ERROR" -> monitor.errored(uid, now, safe);
            default -> throw invalid();
        };
        if (changed == 0) throw new AdminException(HttpStatus.CONFLICT, "UP_DISABLED", "UP 已停用或不存在");
        if (kind.equals("STARTED") || kind.equals("HEARTBEAT")) heartbeat(uid, now);
        if (kind.equals("ERROR")) error(uid, safe, now);
    }

    @Transactional
    void event(String uid, String kind, String message) {
        String locked = admin.lockUp(uid);
        AdminRows.Up up = locked == null ? null : admin.up(uid);
        if (up == null || !up.enabled) {
            throw new AdminException(HttpStatus.CONFLICT, "UP_DISABLED", "UP 已停用或不存在");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("HEARTBEAT".equals(kind)) heartbeat(uid, now);
        else if ("ERROR".equals(kind)) error(uid, safeMessage(message), now);
        else throw invalid();
    }

    private void heartbeat(String uid, LocalDateTime now) {
        String hour = now.truncatedTo(ChronoUnit.HOURS).toString();
        delivery.insertOps("ops:heartbeat:" + uid + ":" + hour, uid,
                "OPS_HEARTBEAT", "UP " + uid + " 监控心跳正常（UTC " + hour + "）", now);
    }

    private void error(String uid, String message, LocalDateTime now) {
        delivery.insertOps("ops:error:" + uid + ":" + UUID.randomUUID(), uid,
                "OPS_ERROR", "UP " + uid + " 扫描或进程错误：" + message, now);
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) return "扫描失败";
        String text = message.replaceAll("[\\r\\n\\t]", " ");
        return text.substring(0, Math.min(text.length(), 500));
    }

    private AdminException invalid() {
        return new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "运维事件无效");
    }
}
