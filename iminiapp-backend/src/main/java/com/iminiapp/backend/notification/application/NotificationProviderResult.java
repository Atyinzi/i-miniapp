package com.iminiapp.backend.notification.application;

public record NotificationProviderResult(
        boolean success,
        boolean retryable,
        String externalMessageId,
        String errorCode,
        String errorMessage) {

    public static NotificationProviderResult sent(String externalMessageId) {
        return new NotificationProviderResult(true, false, externalMessageId, null, null);
    }

    public static NotificationProviderResult retryable(String code, String message) {
        return new NotificationProviderResult(false, true, null, code, message);
    }

    public static NotificationProviderResult rejected(String code, String message) {
        return new NotificationProviderResult(false, false, null, code, message);
    }
}
