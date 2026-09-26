package com.bilicharge.archive;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
final class ContentController {
    private final ContentService service;

    ContentController(ContentService service) {
        this.service = service;
    }

    @GetMapping("/ups")
    ApiEnvelope<List<ContentService.Up>> ups() {
        return new ApiEnvelope<>(service.ups());
    }

    @GetMapping("/dynamics")
    PageEnvelope<ContentService.Dynamic> dynamics(@RequestParam(required = false) String upUid,
                                                   @RequestParam(required = false) String cursor) {
        return service.dynamics(upUid, cursor);
    }

    @GetMapping("/dynamics/{dynamicId}")
    ApiEnvelope<ContentService.Dynamic> dynamic(@PathVariable String dynamicId) {
        return new ApiEnvelope<>(service.dynamic(dynamicId));
    }

    @GetMapping("/dynamics/{dynamicId}/comments")
    PageEnvelope<ContentService.Comment> comments(@PathVariable String dynamicId,
                                                   @RequestParam(required = false) String cursor) {
        return service.comments(dynamicId, null, cursor);
    }

    @GetMapping("/dynamics/{dynamicId}/comments/{rpid}/replies")
    PageEnvelope<ContentService.Comment> replies(@PathVariable String dynamicId,
                                                  @PathVariable String rpid,
                                                  @RequestParam(required = false) String cursor) {
        return service.comments(dynamicId, rpid, cursor);
    }
}
