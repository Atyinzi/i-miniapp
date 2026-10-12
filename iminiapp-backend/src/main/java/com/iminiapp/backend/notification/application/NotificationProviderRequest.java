package com.iminiapp.backend.notification.application;

public record NotificationProviderRequest(
        long taskId,
        long userId,
        String recipient,
        String templateCode,
        String eventType,
        String businessRef,
        String payloadJson) {
}
