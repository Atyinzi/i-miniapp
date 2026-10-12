package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderNotificationOutboxServiceTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationEnqueueService enqueue = mock(NotificationEnqueueService.class);
    private final NotificationProperties properties = new NotificationProperties();

    @Test
    void oneBrokenEventDoesNotPreventFollowingEventAndFailureIsRecorded() {
        when(jdbc.queryForList(contains("SELECT id"), eq(Long.class), eq(100)))
                .thenReturn(List.of(1L, 2L));
        when(jdbc.query(contains("SELECT id,user_id"), any(org.springframework.jdbc.core.RowMapper.class),
                anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(2);
            return List.of(map(invocation, id, 0));
        });
        doThrow(new IllegalStateException(
                "mobile=13800138000 access_token=must-not-leak appsecret=hidden authorization=Bearer_hidden"))
                .when(enqueue).enqueueOrderEvent(argThat(command -> command.orderNo().equals("ORD-1")));
        when(jdbc.queryForList(contains("SELECT attempt_count"), eq(Integer.class), eq(1L)))
                .thenReturn(List.of(0));

        OrderNotificationOutboxService service = new OrderNotificationOutboxService(
                jdbc, enqueue, properties, transactionManager());

        assertEquals(1, service.enqueuePending());
        verify(jdbc).update(contains("SET attempt_count=?"), eq(1),
                eq("RETRY: IllegalStateException: mobile=1********** access_token=*** appsecret=*** authorization=***"),
                any(LocalDateTime.class), eq(1L));
        verify(jdbc).update(contains("SET processed_at=CURRENT_TIMESTAMP"), eq(2L));
        verify(enqueue, atLeastOnce()).enqueueOrderEvent(argThat(command -> command.orderNo().equals("ORD-2")));
    }

    @Test
    void anExhaustedEventIsMovedToDeadLetterWithoutBlockingTheBatch() {
        properties.setMaxAttempts(1);
        when(jdbc.queryForList(contains("SELECT id"), eq(Long.class), eq(100)))
                .thenReturn(List.of(9L));
        when(jdbc.query(contains("SELECT id,user_id"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(9L))).thenAnswer(invocation -> List.of(map(invocation, 9L, 0)));
        doThrow(new IllegalStateException("broken")).when(enqueue).enqueueOrderEvent(any());
        when(jdbc.queryForList(contains("SELECT attempt_count"), eq(Integer.class), eq(9L)))
                .thenReturn(List.of(0));

        OrderNotificationOutboxService service = new OrderNotificationOutboxService(
                jdbc, enqueue, properties, transactionManager());

        assertEquals(0, service.enqueuePending());
        verify(jdbc).update(contains("SET attempt_count=?"), eq(1),
                eq("DEAD_LETTER: IllegalStateException: broken"), eq(9L));
    }

    @SuppressWarnings("unchecked")
    private Object map(InvocationOnMock invocation, long id, int attempt) throws Exception {
        RowMapper<Object> mapper = invocation.getArgument(1);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getLong("user_id")).thenReturn(7L);
        when(rs.getString("order_no")).thenReturn("ORD-" + id);
        when(rs.getString("event_type")).thenReturn("ORDER_PAID");
        when(rs.getString("from_status")).thenReturn("PENDING_PAYMENT");
        when(rs.getString("to_status")).thenReturn("PAID");
        when(rs.getInt("attempt_count")).thenReturn(attempt);
        return mapper.mapRow(rs, 0);
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
        return manager;
    }
}
