package com.iminiapp.backend.notification.domain;

public enum NotificationTaskStatus {
    PENDING,
    PROCESSING,
    RETRY,
    WAITING_CONFIGURATION,
    SENT,
    FAILED,
    SKIPPED_PREFERENCE
}
