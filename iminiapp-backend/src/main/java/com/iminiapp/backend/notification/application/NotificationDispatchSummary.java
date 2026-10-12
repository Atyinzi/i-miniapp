package com.iminiapp.backend.notification.application;

public record NotificationDispatchSummary(
        boolean workerEnabled,
        int recovered,
        int claimed,
        int sent,
        int deferred,
        int failed) {

    public static NotificationDispatchSummary disabled() {
        return new NotificationDispatchSummary(false, 0, 0, 0, 0, 0);
    }
}
