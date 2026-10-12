package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.*;
import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class NotificationDispatchService {
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(access[_-]?token|app[_-]?secret|token|secret|password|passwd|api[_-]?key|authorization)"
                    + "\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final Map<NotificationChannel, NotificationProvider> providers;
    private final Clock clock;

    @Autowired
    public NotificationDispatchService(NotificationRepository repository,
                                       NotificationProperties properties,
                                       List<NotificationProvider> providers) {
        this(repository, properties, providers, Clock.systemDefaultZone());
    }

    NotificationDispatchService(NotificationRepository repository,
                                NotificationProperties properties,
                                List<NotificationProvider> providers,
                                Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                NotificationProvider::channel, provider -> provider,
                (left, right) -> { throw new IllegalStateException("同一通知渠道只能配置一个供应商"); }));
    }

    public NotificationDispatchSummary dispatchDue() {
        if (!properties.isWorkerEnabled()) return NotificationDispatchSummary.disabled();
        LocalDateTime now = LocalDateTime.now(clock);
        int recovered = repository.recoverStaleProcessing(
                now.minus(properties.getProcessingTimeout()), now);
        List<Long> ids = repository.findDueTaskIds(now, properties.getBatchSize());
        int claimed = 0, sent = 0, deferred = 0, failed = 0;
        for (long id : ids) {
            if (!repository.claim(id, now)) continue;
            claimed++;
            Outcome outcome = dispatchClaimed(id, now);
            if (outcome == Outcome.SENT) sent++;
            else if (outcome == Outcome.DEFERRED) deferred++;
            else failed++;
        }
        return new NotificationDispatchSummary(true, recovered, claimed, sent, deferred, failed);
    }

    private Outcome dispatchClaimed(long taskId, LocalDateTime now) {
        NotificationTask task = repository.findTask(taskId)
                .orElseThrow(() -> new IllegalStateException("已认领的通知任务不存在"));
        NotificationProvider provider = providers.get(task.channel());
        if (!properties.isSendingEnabled()) {
            notConfigured(task, "SYSTEM_SENDING_DISABLED", "真实通知外发开关未启用", "SYSTEM_DISABLED");
            return Outcome.DEFERRED;
        }
        if (provider == null || !provider.configured()) {
            notConfigured(task, "PROVIDER_NOT_CONFIGURED", "通知供应商或模板凭据尚未配置",
                    provider == null ? "NO_PROVIDER" : safeProviderCode(provider.providerCode()));
            return Outcome.DEFERRED;
        }
        if (repository.countRecentSent(task.userId(), task.channel(), now.minusMinutes(1))
                >= properties.getPerRecipientPerMinute()) {
            int deliveryAttempt = repository.nextDeliveryAttempt(task.id());
            repository.recordDelivery(task.id(), deliveryAttempt, safeProviderCode(provider.providerCode()),
                    NotificationDeliveryStatus.RATE_LIMITED, null, "RECIPIENT_RATE_LIMITED",
                    "同一用户同一渠道一分钟内通知数量已达上限", 0);
            repository.markRetry(task.id(), task.attemptCount(), now.plusMinutes(1),
                    "RECIPIENT_RATE_LIMITED", "通知已按频率限制延后");
            return Outcome.DEFERRED;
        }
        Optional<String> recipient = repository.findRecipient(task.userId(), task.channel());
        if (recipient.isEmpty()) {
            int nextAttempt = task.attemptCount() + 1;
            repository.recordDelivery(task.id(), repository.nextDeliveryAttempt(task.id()), safeProviderCode(provider.providerCode()),
                    NotificationDeliveryStatus.MISSING_RECIPIENT, null, "MISSING_RECIPIENT",
                    "用户未绑定该通知渠道", 0);
            repository.markFailed(task.id(), nextAttempt, "MISSING_RECIPIENT", "用户未绑定该通知渠道");
            return Outcome.FAILED;
        }
        int providerAttempt = task.attemptCount() + 1;
        long started = System.nanoTime();
        NotificationProviderResult result;
        try {
            result = Objects.requireNonNull(provider.send(new NotificationProviderRequest(
                    task.id(), task.userId(), recipient.get(), task.templateCode(), task.eventType(),
                    task.businessRef(), task.payloadJson())), "通知供应商返回结果不能为空");
        } catch (RuntimeException exception) {
            result = NotificationProviderResult.retryable("PROVIDER_EXCEPTION", "供应商调用异常");
        }
        long durationMs = Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
        String code = safeCode(result.errorCode());
        String message = safeMessage(result.errorMessage(), recipient.get());
        String externalId = safeExternalId(result.externalMessageId());
        if (result.success()) {
            repository.recordDelivery(task.id(), repository.nextDeliveryAttempt(task.id()), safeProviderCode(provider.providerCode()),
                    NotificationDeliveryStatus.SENT, externalId, null, null, durationMs);
            repository.markSent(task.id(), providerAttempt, now);
            return Outcome.SENT;
        }
        boolean retry = result.retryable() && providerAttempt < task.maxAttempts();
        repository.recordDelivery(task.id(), repository.nextDeliveryAttempt(task.id()), safeProviderCode(provider.providerCode()),
                retry ? NotificationDeliveryStatus.RETRYABLE_FAILURE : NotificationDeliveryStatus.FINAL_FAILURE,
                null, code, message, durationMs);
        if (retry) {
            repository.markRetry(task.id(), providerAttempt, now.plus(retryDelay(providerAttempt)), code, message);
            return Outcome.DEFERRED;
        }
        repository.markFailed(task.id(), providerAttempt, code, message);
        return Outcome.FAILED;
    }

    private void notConfigured(NotificationTask task, String code, String message, String providerCode) {
        repository.recordDelivery(task.id(), repository.nextDeliveryAttempt(task.id()), providerCode,
                NotificationDeliveryStatus.NOT_CONFIGURED, null, code, message, 0);
        repository.markWaitingConfiguration(task.id(), code, message);
    }

    private Duration retryDelay(int providerAttempt) {
        long multiplier = 1L << Math.min(10, Math.max(0, providerAttempt - 1));
        Duration calculated = properties.getBaseRetryDelay().multipliedBy(multiplier);
        return calculated.compareTo(Duration.ofHours(24)) > 0 ? Duration.ofHours(24) : calculated;
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) return "PROVIDER_REJECTED";
        String safe = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.-]", "_");
        return safe.substring(0, Math.min(64, safe.length()));
    }

    private String safeMessage(String value, String recipient) {
        String safe = value == null || value.isBlank() ? "通知供应商未返回具体原因" : value.trim();
        if (recipient != null && !recipient.isBlank()) safe = safe.replace(recipient, "***");
        safe = MOBILE.matcher(safe).replaceAll("1**********");
        safe = CREDENTIAL.matcher(safe).replaceAll("$1=***");
        return safe.substring(0, Math.min(255, safe.length()));
    }

    private String safeProviderCode(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN_PROVIDER";
        String safe = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.-]", "_");
        return safe.substring(0, Math.min(64, safe.length()));
    }

    private String safeExternalId(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) return null;
        return value;
    }

    private enum Outcome { SENT, DEFERRED, FAILED }
}
