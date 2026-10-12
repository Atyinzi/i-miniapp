package com.iminiapp.backend.notification.infrastructure;

import com.iminiapp.backend.notification.application.*;
import com.iminiapp.backend.notification.domain.NotificationChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.notification.sms.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledSmsProvider implements NotificationProvider {
    @Override public NotificationChannel channel() { return NotificationChannel.SMS; }
    @Override public String providerCode() { return "SMS_NOT_CONFIGURED"; }
    @Override public boolean configured() { return false; }
    @Override public NotificationProviderResult send(NotificationProviderRequest request) {
        return NotificationProviderResult.rejected("PROVIDER_NOT_CONFIGURED", "短信供应商尚未配置");
    }
}
