package com.iminiapp.backend.notification.infrastructure;

import com.iminiapp.backend.notification.application.NotificationDispatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationDispatchScheduler {
    private final NotificationDispatchService service;

    public NotificationDispatchScheduler(NotificationDispatchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.notification.scan-ms:30000}")
    public void dispatch() {
        service.dispatchDue();
    }
}
