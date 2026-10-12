package com.iminiapp.backend.notification.infrastructure;

import com.iminiapp.backend.notification.application.*;
import com.iminiapp.backend.notification.domain.NotificationChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.notification.wechat-enabled", havingValue = "false", matchIfMissing = true)
public class DisabledWechatSubscriptionProvider implements NotificationProvider {
    @Override public NotificationChannel channel() { return NotificationChannel.WECHAT_SUBSCRIPTION; }
    @Override public String providerCode() { return "WECHAT_NOT_CONFIGURED"; }
    @Override public boolean configured() { return false; }
    @Override public NotificationProviderResult send(NotificationProviderRequest request) {
        return NotificationProviderResult.rejected("PROVIDER_NOT_CONFIGURED", "微信订阅消息供应商尚未配置");
    }
}
