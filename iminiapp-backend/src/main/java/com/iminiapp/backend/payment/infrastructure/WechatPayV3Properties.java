package com.iminiapp.backend.payment.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.wechat-pay-v3")
public class WechatPayV3Properties {
    private boolean enabled;
    private String appId;
    private String merchantId;
    private String merchantSerialNumber;
    private String privateKeyPath;
    private String apiV3Key;
    private String platformPublicKeyPath;
    private String platformKeyId;
    private String paymentNotifyUrl;
    private String refundNotifyUrl;
    private String baseUrl = "https://api.mch.weixin.qq.com";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Duration callbackTolerance = Duration.ofMinutes(5);

    public List<String> configurationErrors() {
        List<String> errors = new ArrayList<>();
        require(errors, "app-id", appId);
        require(errors, "merchant-id", merchantId);
        require(errors, "merchant-serial-number", merchantSerialNumber);
        require(errors, "private-key-path", privateKeyPath);
        require(errors, "api-v3-key", apiV3Key);
        require(errors, "platform-public-key-path", platformPublicKeyPath);
        require(errors, "platform-key-id", platformKeyId);
        require(errors, "payment-notify-url", paymentNotifyUrl);
        require(errors, "refund-notify-url", refundNotifyUrl);
        if (StringUtils.hasText(apiV3Key) && apiV3Key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32) {
            errors.add("api-v3-key must be exactly 32 UTF-8 bytes");
        }
        requireReadableFile(errors, "private-key-path", privateKeyPath);
        requireReadableFile(errors, "platform-public-key-path", platformPublicKeyPath);
        requireHttps(errors, "payment-notify-url", paymentNotifyUrl);
        requireHttps(errors, "refund-notify-url", refundNotifyUrl);
        requireHttps(errors, "base-url", baseUrl);
        return errors;
    }

    private static void require(List<String> errors, String name, String value) {
        if (!StringUtils.hasText(value)) errors.add(name + " is missing");
    }

    private static void requireReadableFile(List<String> errors, String name, String value) {
        if (!StringUtils.hasText(value)) return;
        try {
            if (!Files.isRegularFile(Path.of(value)) || !Files.isReadable(Path.of(value))) {
                errors.add(name + " is not a readable regular file");
            }
        } catch (RuntimeException exception) {
            errors.add(name + " is not a valid file path");
        }
    }

    private static void requireHttps(List<String> errors, String name, String value) {
        if (!StringUtils.hasText(value)) return;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                errors.add(name + " must be an absolute HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            errors.add(name + " must be an absolute HTTPS URL");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getMerchantSerialNumber() { return merchantSerialNumber; }
    public void setMerchantSerialNumber(String merchantSerialNumber) { this.merchantSerialNumber = merchantSerialNumber; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
    public String getApiV3Key() { return apiV3Key; }
    public void setApiV3Key(String apiV3Key) { this.apiV3Key = apiV3Key; }
    public String getPlatformPublicKeyPath() { return platformPublicKeyPath; }
    public void setPlatformPublicKeyPath(String platformPublicKeyPath) { this.platformPublicKeyPath = platformPublicKeyPath; }
    public String getPlatformKeyId() { return platformKeyId; }
    public void setPlatformKeyId(String platformKeyId) { this.platformKeyId = platformKeyId; }
    public String getPaymentNotifyUrl() { return paymentNotifyUrl; }
    public void setPaymentNotifyUrl(String paymentNotifyUrl) { this.paymentNotifyUrl = paymentNotifyUrl; }
    public String getRefundNotifyUrl() { return refundNotifyUrl; }
    public void setRefundNotifyUrl(String refundNotifyUrl) { this.refundNotifyUrl = refundNotifyUrl; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getCallbackTolerance() { return callbackTolerance; }
    public void setCallbackTolerance(Duration callbackTolerance) { this.callbackTolerance = callbackTolerance; }
}
