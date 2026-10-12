package com.iminiapp.backend.notification.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {
    /** 双重安全开关之一：默认禁止真实外发。 */
    private boolean sendingEnabled = false;
    /** 双重安全开关之二：默认不启动投递扫描。 */
    private boolean workerEnabled = false;
    private int batchSize = 50;
    private int maxAttempts = 5;
    private int perRecipientPerMinute = 3;
    private Duration baseRetryDelay = Duration.ofMinutes(1);
    private Duration processingTimeout = Duration.ofMinutes(10);
    /** 微信通知 HTTP 连接和响应超时，防止调度线程被外部网络长期占用。 */
    private Duration wechatConnectTimeout = Duration.ofSeconds(5);
    private Duration wechatReadTimeout = Duration.ofSeconds(10);
    /** 事件类型到微信订阅模板 ID 的映射；未配置时任务停留在 WAITING_CONFIGURATION。 */
    private Map<String, String> wechatTemplates = new HashMap<>();
    /** 事件类型到短信模板代码的映射；未配置时任务停留在 WAITING_CONFIGURATION。 */
    private Map<String, String> smsTemplates = new HashMap<>();
    /** event -> "payloadKey:wechatKeywordId,..."，例如 orderNo:character_string1,status:phrase2。 */
    private Map<String, String> wechatFields = new HashMap<>();
    /** 真实微信订阅消息 provider；默认 false，必须与总发送开关一起开启。 */
    private boolean wechatEnabled = false;
    /** 微信订阅消息跳转的小程序版本，仅允许微信定义的三个固定值。 */
    private String wechatMiniprogramState = "formal";

    public boolean isSendingEnabled() { return sendingEnabled; }
    public void setSendingEnabled(boolean sendingEnabled) { this.sendingEnabled = sendingEnabled; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = bounded(batchSize, 1, 500, "batch-size"); }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = bounded(maxAttempts, 1, 20, "max-attempts"); }
    public int getPerRecipientPerMinute() { return perRecipientPerMinute; }
    public void setPerRecipientPerMinute(int value) { this.perRecipientPerMinute = bounded(value, 1, 100, "per-recipient-per-minute"); }
    public Duration getBaseRetryDelay() { return baseRetryDelay; }
    public void setBaseRetryDelay(Duration value) { this.baseRetryDelay = positive(value, "base-retry-delay"); }
    public Duration getProcessingTimeout() { return processingTimeout; }
    public void setProcessingTimeout(Duration value) { this.processingTimeout = positive(value, "processing-timeout"); }
    public Duration getWechatConnectTimeout() { return wechatConnectTimeout; }
    public void setWechatConnectTimeout(Duration value) {
        this.wechatConnectTimeout = httpTimeout(value, "wechat-connect-timeout");
    }
    public Duration getWechatReadTimeout() { return wechatReadTimeout; }
    public void setWechatReadTimeout(Duration value) {
        this.wechatReadTimeout = httpTimeout(value, "wechat-read-timeout");
    }
    public Map<String, String> getWechatTemplates() { return wechatTemplates; }
    public void setWechatTemplates(Map<String, String> value) { this.wechatTemplates = safeMap(value); }
    public Map<String, String> getSmsTemplates() { return smsTemplates; }
    public void setSmsTemplates(Map<String, String> value) { this.smsTemplates = safeMap(value); }
    public boolean isWechatEnabled() { return wechatEnabled; }
    public void setWechatEnabled(boolean wechatEnabled) { this.wechatEnabled = wechatEnabled; }
    public String getWechatMiniprogramState() { return wechatMiniprogramState; }
    public void setWechatMiniprogramState(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("formal", "developer", "trial").contains(normalized)) {
            throw new IllegalArgumentException(
                    "app.notification.wechat-miniprogram-state 仅支持 formal、developer 或 trial");
        }
        this.wechatMiniprogramState = normalized;
    }
    public Map<String, String> getWechatFields() { return wechatFields; }
    public void setWechatFields(Map<String, String> value) { this.wechatFields = safeMap(value); }

    public String templateFor(String eventType, boolean wechat) {
        if (eventType == null) return null;
        String expected = eventType.trim().toUpperCase(Locale.ROOT);
        Map<String, String> source = wechat ? wechatTemplates : smsTemplates;
        String template = source.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().trim().toUpperCase(Locale.ROOT).equals(expected))
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isBlank())
                .map(String::trim).findFirst().orElse(null);
        if (wechat && (template == null || wechatFieldMapping(eventType).isEmpty())) return null;
        return template;
    }

    public Map<String, String> wechatFieldMapping(String eventType) {
        if (eventType == null) return Map.of();
        String expected = eventType.trim().toUpperCase(Locale.ROOT);
        String raw = wechatFields.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().trim().toUpperCase(Locale.ROOT).equals(expected))
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(null);
        if (raw == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String[] values = pair.trim().split(":", 2);
            if (values.length != 2) return Map.of();
            String payloadKey = values[0].trim();
            String keywordId = values[1].trim();
            if (!Set.of("orderNo", "status", "previousStatus").contains(payloadKey)
                    || !keywordId.matches("[A-Za-z_]+[0-9]{1,3}")
                    || result.containsValue(keywordId)) return Map.of();
            result.put(payloadKey, keywordId);
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private int bounded(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException("app.notification." + name + " 超出安全范围");
        return value;
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.notification." + name + " 必须为正数");
        }
        return value;
    }

    private Duration httpTimeout(Duration value, String name) {
        Duration timeout = positive(value, name);
        if (timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("app.notification." + name + " 最长为1分钟");
        }
        return timeout;
    }

    private Map<String, String> safeMap(Map<String, String> value) {
        return value == null ? new HashMap<>() : new HashMap<>(value);
    }
}
