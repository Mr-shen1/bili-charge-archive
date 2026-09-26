package com.bilicharge.archive;

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
    private final OpsEventService ops;

    MonitorController(MonitorMapper mapper, AdminMapper admin, OpsEventService ops) {
        this.mapper = mapper;
        this.admin = admin;
        this.ops = ops;
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
        if (status == null) throw new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "扫描状态无效");
        ops.status(uid, status.kind, status.message);
        return new ApiEnvelope<>("OK");
    }

    @PostMapping("/{uid}/ops-events")
    ApiEnvelope<String> opsEvent(@PathVariable String uid, @RequestBody Status event) {
        if (event == null) throw new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "运维事件无效");
        ops.event(uid, event.kind, event.message);
        return new ApiEnvelope<>("OK");
    }

    record Config(String uid, boolean enabled, boolean defaultAllConfigured,
                  boolean defaultUpConfigured, List<Route> fixedRoutes, List<MonitorMapper.Scan> scans) {}
    record Route(String dynamicId, boolean allConfigured, boolean upConfigured) {}
    record Status(String kind, String message) {}
}
