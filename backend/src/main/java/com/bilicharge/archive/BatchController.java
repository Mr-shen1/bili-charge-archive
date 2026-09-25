package com.bilicharge.archive;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ups/{uid}")
final class BatchController {
    private final BatchService service;

    BatchController(BatchService service) {
        this.service = service;
    }

    @PostMapping("/batches")
    ApiEnvelope<BatchService.BatchResult> submit(@PathVariable String uid, @RequestBody BatchRequest request) {
        return new ApiEnvelope<>(service.submit(uid, request));
    }
}
