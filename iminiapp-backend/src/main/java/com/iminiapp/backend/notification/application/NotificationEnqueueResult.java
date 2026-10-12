package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.NotificationChannel;
import com.iminiapp.backend.notification.domain.NotificationTaskStatus;

public record NotificationEnqueueResult(
        long taskId,
        NotificationChannel channel,
        NotificationTaskStatus status,
        boolean deduplicated) {
}
