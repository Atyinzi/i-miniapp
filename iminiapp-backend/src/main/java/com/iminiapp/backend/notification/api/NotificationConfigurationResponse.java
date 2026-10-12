package com.iminiapp.backend.notification.api;

import java.util.List;

public record NotificationConfigurationResponse(
        boolean wechatSubscriptionConfigured,
        List<WechatTemplate> wechatTemplates) {
    public record WechatTemplate(String eventType, String templateId) { }
}
