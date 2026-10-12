package com.iminiapp.backend.notification.domain;

import java.time.LocalDateTime;

public record NotificationDelivery(
        long id,
        long taskId,
        int attemptNo,
        String providerCode,
        NotificationDeliveryStatus status,
        String externalMessageId,
        String errorCode,
        String errorMessage,
        long durationMs,
        LocalDateTime attemptedAt) {
}
