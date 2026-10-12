package com.iminiapp.backend.notification.domain;

public enum NotificationDeliveryStatus {
    SENT,
    RETRYABLE_FAILURE,
    FINAL_FAILURE,
    NOT_CONFIGURED,
    RATE_LIMITED,
    MISSING_RECIPIENT
}
