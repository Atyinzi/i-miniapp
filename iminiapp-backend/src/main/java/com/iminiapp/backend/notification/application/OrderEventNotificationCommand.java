package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.NotificationChannel;

import java.util.Map;
import java.util.Set;

/** 订单模块调用通知模块的稳定入队契约，不执行同步外发。 */
public record OrderEventNotificationCommand(
        long userId,
        String orderNo,
        String eventType,
        String templateCode,
        Map<String, String> templateData,
        Set<NotificationChannel> channels,
        String idempotencyKey) {
}
