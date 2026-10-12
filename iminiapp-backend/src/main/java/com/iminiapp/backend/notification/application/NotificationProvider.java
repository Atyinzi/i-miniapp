package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.NotificationChannel;

/**
 * 第三方通知供应商适配点。实现类必须从运行时密钥服务或环境变量读取凭据，
 * 不得把密钥写入通知任务、投递记录或日志。
 */
public interface NotificationProvider {
    NotificationChannel channel();

    String providerCode();

    boolean configured();

    NotificationProviderResult send(NotificationProviderRequest request);
}
