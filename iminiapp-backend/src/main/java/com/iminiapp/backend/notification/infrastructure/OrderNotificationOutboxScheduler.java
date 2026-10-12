package com.iminiapp.backend.notification.infrastructure;

import com.iminiapp.backend.notification.application.OrderNotificationOutboxService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderNotificationOutboxScheduler {
    private final OrderNotificationOutboxService service;

    public OrderNotificationOutboxScheduler(OrderNotificationOutboxService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.notification.outbox-scan-ms:15000}")
    public void enqueuePending() {
        service.enqueuePending();
    }
}
