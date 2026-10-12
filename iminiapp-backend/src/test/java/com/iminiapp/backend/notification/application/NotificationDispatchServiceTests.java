package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.*;
import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationDispatchServiceTests {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-12T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 12, 0);

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationProvider provider = mock(NotificationProvider.class);
    private final NotificationProperties properties = new NotificationProperties();

    @Test
    void workerAndSendingAreDisabledByDefault() {
        NotificationDispatchService service = new NotificationDispatchService(
                repository, properties, List.of(), CLOCK);

        NotificationDispatchSummary summary = service.dispatchDue();

        assertFalse(summary.workerEnabled());
        verifyNoInteractions(repository, provider);
    }

    @Test
    void enabledWorkerStillNeverCallsProviderWhenSendingSwitchIsOff() {
        properties.setWorkerEnabled(true);
        dueTask(task(1L, 0, 5));
        when(provider.channel()).thenReturn(NotificationChannel.WECHAT_SUBSCRIPTION);
        when(provider.providerCode()).thenReturn("REAL_PROVIDER");
        when(provider.configured()).thenReturn(true);
        when(repository.nextDeliveryAttempt(1L)).thenReturn(1);

        NotificationDispatchSummary summary = service().dispatchDue();

        assertEquals(1, summary.deferred());
        verify(provider, never()).send(any());
        verify(repository).markWaitingConfiguration(1L, "SYSTEM_SENDING_DISABLED", "真实通知外发开关未启用");
        verify(repository).recordDelivery(1L, 1, "SYSTEM_DISABLED",
                NotificationDeliveryStatus.NOT_CONFIGURED, null,
                "SYSTEM_SENDING_DISABLED", "真实通知外发开关未启用", 0);
    }

    @Test
    void successfulProviderCallMarksTaskSent() {
        properties.setWorkerEnabled(true);
        properties.setSendingEnabled(true);
        dueTask(task(2L, 0, 5));
        when(provider.channel()).thenReturn(NotificationChannel.WECHAT_SUBSCRIPTION);
        when(provider.providerCode()).thenReturn("wechat-v3");
        when(provider.configured()).thenReturn(true);
        when(repository.countRecentSent(7L, NotificationChannel.WECHAT_SUBSCRIPTION, NOW.minusMinutes(1)))
                .thenReturn(0);
        when(repository.findRecipient(7L, NotificationChannel.WECHAT_SUBSCRIPTION))
                .thenReturn(Optional.of("openid-only-in-memory"));
        when(provider.send(any())).thenReturn(NotificationProviderResult.sent("msg-123"));
        when(repository.nextDeliveryAttempt(2L)).thenReturn(1);

        NotificationDispatchSummary summary = service().dispatchDue();

        assertEquals(1, summary.sent());
        verify(repository).markSent(2L, 1, NOW);
        verify(repository).recordDelivery(eq(2L), eq(1), eq("WECHAT-V3"),
                eq(NotificationDeliveryStatus.SENT), eq("msg-123"), isNull(), isNull(), anyLong());
    }

    @Test
    void retryableFailureUsesBackoffAndRedactsRecipientFromAuditMessage() {
        properties.setWorkerEnabled(true);
        properties.setSendingEnabled(true);
        dueTask(task(3L, 1, 5, NotificationChannel.SMS));
        when(provider.channel()).thenReturn(NotificationChannel.SMS);
        when(provider.providerCode()).thenReturn("sms-provider");
        when(provider.configured()).thenReturn(true);
        when(repository.countRecentSent(7L, NotificationChannel.SMS, NOW.minusMinutes(1))).thenReturn(0);
        when(repository.findRecipient(7L, NotificationChannel.SMS)).thenReturn(Optional.of("13800138000"));
        when(provider.send(any())).thenReturn(NotificationProviderResult.retryable(
                "temporary", "recipient=13800138000 token=very-secret-value"));
        when(repository.nextDeliveryAttempt(3L)).thenReturn(2);

        NotificationDispatchSummary summary = service().dispatchDue();

        assertEquals(1, summary.deferred());
        verify(repository).markRetry(3L, 2, NOW.plusMinutes(2),
                "TEMPORARY", "recipient=*** token=***");
        verify(repository).recordDelivery(eq(3L), eq(2), eq("SMS-PROVIDER"),
                eq(NotificationDeliveryStatus.RETRYABLE_FAILURE), isNull(),
                eq("TEMPORARY"), eq("recipient=*** token=***"), anyLong());
    }

    private NotificationDispatchService service() {
        return new NotificationDispatchService(repository, properties, List.of(provider), CLOCK);
    }

    private void dueTask(NotificationTask task) {
        when(repository.recoverStaleProcessing(any(), eq(NOW))).thenReturn(0);
        when(repository.findDueTaskIds(NOW, 50)).thenReturn(List.of(task.id()));
        when(repository.claim(task.id(), NOW)).thenReturn(true);
        when(repository.findTask(task.id())).thenReturn(Optional.of(task));
    }

    private NotificationTask task(long id, int attempt, int maxAttempts) {
        return task(id, attempt, maxAttempts, NotificationChannel.WECHAT_SUBSCRIPTION);
    }

    private NotificationTask task(long id, int attempt, int maxAttempts, NotificationChannel channel) {
        return new NotificationTask(id, 7L, "ORDER", "ORD-100", "ORDER.PAID",
                channel, "ORDER_PAID", "{}",
                NotificationTaskStatus.PROCESSING, attempt, maxAttempts, NOW,
                null, null, null, NOW.minusMinutes(2), NOW);
    }
}
