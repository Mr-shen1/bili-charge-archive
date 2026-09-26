package com.bilicharge.archive;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class DeliveryCleanup {
    private final DeliveryService service;

    DeliveryCleanup(DeliveryService service) { this.service = service; }

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    void run() { service.cleanup(); }
}
