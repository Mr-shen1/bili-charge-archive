package com.bilicharge.archive;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/deliveries")
final class DeliveryController {
    private final DeliveryService service;

    DeliveryController(DeliveryService service) { this.service = service; }

    @PostMapping("/claim")
    ApiEnvelope<DeliveryService.Claim> claim(@RequestBody ClaimRequest request) {
        return new ApiEnvelope<>(service.claim(request.upUid(), request.workerId()));
    }

    @PostMapping("/{eventId}/{role}/result")
    ApiEnvelope<String> result(@PathVariable long eventId, @PathVariable String role,
                               @RequestBody DeliveryService.Result request) {
        service.result(eventId, role, request);
        return new ApiEnvelope<>("OK");
    }

    record ClaimRequest(String upUid, String workerId) {}
}
