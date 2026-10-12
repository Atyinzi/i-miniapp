package com.iminiapp.backend.notification.domain;

import java.time.LocalDateTime;

public record NotificationTask(
        long id,
        long userId,
        String businessType,
        String businessRef,
        String eventType,
        NotificationChannel channel,
        String templateCode,
        String payloadJson,
        NotificationTaskStatus status,
        int attemptCount,
        int maxAttempts,
        LocalDateTime nextAttemptAt,
        String lastErrorCode,
        String lastErrorMessage,
        LocalDateTime sentAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
