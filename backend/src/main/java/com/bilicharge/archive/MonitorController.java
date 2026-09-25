package com.bilicharge.archive;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ups")
final class MonitorController {
    private final MonitorMapper mapper;
    private final AdminMapper admin;

    MonitorController(MonitorMapper mapper, AdminMapper admin) {
        this.mapper = mapper;
        this.admin = admin;
    }

    @GetMapping
    ApiEnvelope<List<String>> enabled(@RequestParam(defaultValue = "true") boolean enabled) {
        if (!enabled) throw new AdminException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "仅支持查询已启用 UP");
        return new ApiEnvelope<>(mapper.enabledUids());
    }

    @GetMapping("/{uid}/config")
    ApiEnvelope<Config> config(@PathVariable String uid) {
        AdminRows.Up up = admin.up(uid);
        if (up == null) throw new AdminException(HttpStatus.NOT_FOUND, "NOT_FOUND", "UP 不存在");
        return new ApiEnvelope<>(new Config(uid, up.enabled, up.defaultAllGroupId != null,
                up.defaultUpGroupId != null, admin.routes(uid).stream()
                        .map(route -> new Route(route.dynamicId, route.allGroupId != null,
                                route.upGroupId != null)).toList(), mapper.scans(uid)));
    }

    @PostMapping("/{uid}/worker-status")
    ApiEnvelope<String> status(@PathVariable String uid, @RequestBody Status status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (status == null || status.kind == null) {
            throw new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "扫描状态无效");
        }
        int changed = switch (status.kind) {
            case "HEARTBEAT" -> mapper.heartbeat(uid, now);
            case "STARTED" -> mapper.started(uid, now);
            case "SUCCEEDED" -> mapper.succeeded(uid, now);
            case "ERROR" -> mapper.errored(uid, now, safeMessage(status.message));
            default -> throw new AdminException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_STATUS", "扫描状态无效");
        };
        if (changed == 0) throw new AdminException(HttpStatus.CONFLICT, "UP_DISABLED", "UP 已停用或不存在");
        return new ApiEnvelope<>("OK");
    }

    // Error text is display-only; reject long upstream payloads and never persist a request header.
    private String safeMessage(String message) {
        if (message == null) return "扫描失败";
        return message.substring(0, Math.min(message.length(), 500));
    }

    record Config(String uid, boolean enabled, boolean defaultAllConfigured,
                  boolean defaultUpConfigured, List<Route> fixedRoutes, List<MonitorMapper.Scan> scans) {}
    record Route(String dynamicId, boolean allConfigured, boolean upConfigured) {}
    record Status(String kind, String message) {}
}
