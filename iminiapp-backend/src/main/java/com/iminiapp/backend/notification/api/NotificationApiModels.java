package com.iminiapp.backend.notification.api;

import com.iminiapp.backend.notification.domain.NotificationChannel;

import java.util.List;

public final class NotificationApiModels {
    private NotificationApiModels() { }

    public record PreferenceItem(String eventType, NotificationChannel channel, boolean enabled) { }
    public record PreferenceUpdateRequest(List<PreferenceItem> preferences) { }
}
