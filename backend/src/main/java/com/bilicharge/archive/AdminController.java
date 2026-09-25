package com.bilicharge.archive;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
final class AdminController {
    private final AdminService service;

    AdminController(AdminService service) { this.service = service; }

    @GetMapping("/groups")
    ApiEnvelope<List<AdminService.GroupView>> groups() { return new ApiEnvelope<>(service.groups()); }

    @PostMapping("/groups")
    ApiEnvelope<AdminService.GroupView> createGroup(@RequestBody GroupRequest body) {
        return new ApiEnvelope<>(service.createGroup(body.name(), body.webhook()));
    }

    @PatchMapping("/groups/{id}")
    ApiEnvelope<AdminService.GroupView> updateGroup(@PathVariable long id, @RequestBody GroupRequest body) {
        return new ApiEnvelope<>(service.updateGroup(id, body.name(), body.webhook()));
    }

    @DeleteMapping("/groups/{id}")
    ApiEnvelope<Map<String, Boolean>> deleteGroup(@PathVariable long id) {
        service.deleteGroup(id);
        return new ApiEnvelope<>(Map.of("deleted", true));
    }

    @PostMapping("/ups/preview")
    ApiEnvelope<BiliPreviewClient.User> previewUp(@RequestBody PreviewRequest body) {
        return new ApiEnvelope<>(service.previewUp(body.input()));
    }

    @GetMapping("/ups")
    ApiEnvelope<List<AdminService.UpView>> ups() { return new ApiEnvelope<>(service.ups()); }

    @PostMapping("/ups")
    ApiEnvelope<AdminService.UpView> createUp(@RequestBody UpRequest body) {
        return new ApiEnvelope<>(service.createUp(body.uid(), body.opsGroupId(),
                body.defaultAllGroupId(), body.defaultUpGroupId()));
    }

    @PatchMapping("/ups/{uid}")
    ApiEnvelope<AdminService.UpView> updateUp(@PathVariable String uid, @RequestBody JsonNode body) {
        return new ApiEnvelope<>(service.updateUp(uid, body));
    }

    @GetMapping("/ups/{uid}/status")
    ApiEnvelope<AdminService.UpStatus> status(@PathVariable String uid) {
        return new ApiEnvelope<>(service.status(uid));
    }

    @GetMapping("/ups/{uid}/routes")
    ApiEnvelope<List<AdminService.RouteView>> routes(@PathVariable String uid) {
        return new ApiEnvelope<>(service.routes(uid));
    }

    @PostMapping("/ups/{uid}/routes/preview")
    ApiEnvelope<BiliPreviewClient.Dynamic> previewRoute(@PathVariable String uid, @RequestBody PreviewRequest body) {
        return new ApiEnvelope<>(service.previewRoute(uid, body.input()));
    }

    @PutMapping("/ups/{uid}/routes/{dynamicId}")
    ApiEnvelope<AdminService.RouteView> saveRoute(@PathVariable String uid, @PathVariable String dynamicId,
                                                    @RequestBody RouteRequest body) {
        return new ApiEnvelope<>(service.saveRoute(uid, dynamicId, body.allGroupId(), body.upGroupId()));
    }

    @DeleteMapping("/ups/{uid}/routes/{dynamicId}")
    ApiEnvelope<Map<String, Boolean>> deleteRoute(@PathVariable String uid, @PathVariable String dynamicId) {
        service.deleteRoute(uid, dynamicId);
        return new ApiEnvelope<>(Map.of("deleted", true));
    }

    record GroupRequest(String name, String webhook) {}
    record PreviewRequest(String input) {}
    record UpRequest(String uid, Long opsGroupId, Long defaultAllGroupId, Long defaultUpGroupId) {}
    record RouteRequest(Long allGroupId, Long upGroupId) {}
}
