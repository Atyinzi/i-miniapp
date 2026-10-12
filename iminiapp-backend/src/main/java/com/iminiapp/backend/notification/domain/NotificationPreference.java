package com.iminiapp.backend.notification.domain;

public record NotificationPreference(String eventType, NotificationChannel channel, boolean enabled) {
}
