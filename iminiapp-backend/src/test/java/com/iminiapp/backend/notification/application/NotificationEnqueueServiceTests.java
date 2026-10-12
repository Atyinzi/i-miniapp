package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.*;
import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEnqueueServiceTests {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationProperties properties = new NotificationProperties();
    private final NotificationEnqueueService service = new NotificationEnqueueService(
            repository, properties, new ObjectMapper());

    @Test
    void enqueueIsIdempotentAndDoesNotPersistRecipientData() {
        when(repository.preferenceEnabled(7L, "ORDER.PAID", NotificationChannel.WECHAT_SUBSCRIPTION))
                .thenReturn(true);
        when(repository.insertTask(anyLong(), anyString(), anyString(), anyString(),
                any(NotificationChannel.class), anyString(), anyString(), anyString(),
                any(NotificationTaskStatus.class), anyInt())).thenReturn(41L);

        List<NotificationEnqueueResult> result = service.enqueueOrderEvent(new OrderEventNotificationCommand(
                7L, "ORD-100", "order.paid", "wxTmpl_Aa12",
                Map.of("amount", "88.00", "serviceTime", "2026-08-12 18:00"),
                Set.of(NotificationChannel.WECHAT_SUBSCRIPTION), "payment-1"));

        assertEquals(1, result.size());
        assertEquals(NotificationTaskStatus.PENDING, result.get(0).status());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(repository).insertTask(eq(7L), eq("ORDER"), eq("ORD-100"), eq("ORDER.PAID"),
                eq(NotificationChannel.WECHAT_SUBSCRIPTION), eq("wxTmpl_Aa12"),
                matches("[0-9a-f]{64}"), payload.capture(), eq(NotificationTaskStatus.PENDING), eq(5));
        assertFalse(payload.getValue().contains("openid"));
        assertFalse(payload.getValue().contains("mobile"));
    }

    @Test
    void missingTemplateStaysWaitingForConfiguration() {
        when(repository.preferenceEnabled(anyLong(), anyString(), any())).thenReturn(true);
        when(repository.insertTask(anyLong(), anyString(), anyString(), anyString(), any(),
                isNull(), anyString(), anyString(), any(), anyInt())).thenReturn(42L);

        NotificationEnqueueResult result = service.enqueueOrderEvent(new OrderEventNotificationCommand(
                7L, "ORD-101", "ORDER.CREATED", null, Map.of(),
                Set.of(NotificationChannel.SMS), "create-1")).get(0);

        assertEquals(NotificationTaskStatus.WAITING_CONFIGURATION, result.status());
    }

    @Test
    void rejectsPlaintextSensitiveDataBeforeDatabaseWrite() {
        assertThrows(BusinessException.class, () -> service.enqueueOrderEvent(
                new OrderEventNotificationCommand(7L, "ORD-102", "ORDER.PAID", "ORDER_PAID",
                        Map.of("mobile", "13800138000"), Set.of(NotificationChannel.SMS), "paid-2")));
        assertThrows(BusinessException.class, () -> service.enqueueOrderEvent(
                new OrderEventNotificationCommand(7L, "ORD-102", "ORDER.PAID", "ORDER_PAID",
                        Map.of("remark", "联系13800138000"), Set.of(NotificationChannel.SMS), "paid-3")));
        verify(repository, never()).insertTask(anyLong(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void duplicateKeyReturnsExistingTaskWithoutCreatingAnotherNotification() {
        when(repository.preferenceEnabled(anyLong(), anyString(), any())).thenReturn(true);
        when(repository.insertTask(anyLong(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), any(), anyInt()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        NotificationTask existing = task(88L, NotificationTaskStatus.SENT);
        when(repository.findByDeduplicationKey(anyString())).thenReturn(Optional.of(existing));

        NotificationEnqueueResult result = service.enqueueOrderEvent(new OrderEventNotificationCommand(
                7L, "ORD-100", "ORDER.PAID", "ORDER_PAID", Map.of("amount", "88"),
                Set.of(NotificationChannel.WECHAT_SUBSCRIPTION), "payment-1")).get(0);

        assertTrue(result.deduplicated());
        assertEquals(88L, result.taskId());
        assertEquals(NotificationTaskStatus.SENT, result.status());
    }

    private NotificationTask task(long id, NotificationTaskStatus status) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 12, 0);
        return new NotificationTask(id, 7L, "ORDER", "ORD-100", "ORDER.PAID",
                NotificationChannel.WECHAT_SUBSCRIPTION, "ORDER_PAID", "{}", status,
                1, 5, now, null, null, status == NotificationTaskStatus.SENT ? now : null, now, now);
    }
}
