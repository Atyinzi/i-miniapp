package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.NotificationChannel;
import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class OrderNotificationOutboxService {
    private static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final NotificationEnqueueService enqueueService;
    private final NotificationProperties properties;
    private final TransactionTemplate requiresNew;

    public OrderNotificationOutboxService(JdbcTemplate jdbc, NotificationEnqueueService enqueueService,
                                          NotificationProperties properties,
                                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.enqueueService = enqueueService;
        this.properties = properties;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int enqueuePending() {
        List<Long> ids = jdbc.queryForList("""
                SELECT id
                FROM bf_order_notification_outbox
                WHERE processed_at IS NULL AND dead_lettered_at IS NULL
                  AND next_attempt_at<=CURRENT_TIMESTAMP
                ORDER BY id LIMIT ?
                """, Long.class, BATCH_SIZE);
        int processed = 0;
        for (Long id : ids) {
            try {
                Boolean completed = requiresNew.execute(status -> processOne(id));
                if (Boolean.TRUE.equals(completed)) processed++;
            } catch (RuntimeException exception) {
                try {
                    requiresNew.executeWithoutResult(status -> recordFailure(id, exception));
                } catch (RuntimeException ignored) {
                    // A database outage will be retried by the next scheduler run. Do not stop other selected rows.
                }
            }
        }
        return processed;
    }

    private boolean processOne(long id) {
        List<OrderEvent> rows = jdbc.query("""
                SELECT id,user_id,order_no,event_type,from_status,to_status,attempt_count
                FROM bf_order_notification_outbox
                WHERE id=? AND processed_at IS NULL AND dead_lettered_at IS NULL
                  AND next_attempt_at<=CURRENT_TIMESTAMP
                FOR UPDATE
                """, (rs, row) -> new OrderEvent(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("order_no"), rs.getString("event_type"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getInt("attempt_count")), id);
        if (rows.isEmpty()) return false;
        OrderEvent value = rows.get(0);
        enqueue(value, NotificationChannel.WECHAT_SUBSCRIPTION,
                properties.templateFor(value.eventType(), true));
        enqueue(value, NotificationChannel.SMS,
                properties.templateFor(value.eventType(), false));
        jdbc.update("""
                UPDATE bf_order_notification_outbox
                SET processed_at=CURRENT_TIMESTAMP,attempt_count=attempt_count+1,last_error=NULL
                WHERE id=? AND processed_at IS NULL
                """, value.id());
        return true;
    }

    private void enqueue(OrderEvent value, NotificationChannel channel, String templateCode) {
        enqueueService.enqueueOrderEvent(new OrderEventNotificationCommand(
                value.userId(), value.orderNo(), value.eventType(), templateCode,
                Map.of("orderNo", value.orderNo(), "status", value.toStatus(),
                        "previousStatus", value.fromStatus() == null ? "" : value.fromStatus()),
                Set.of(channel), "OUTBOX:" + value.id()));
    }

    private void recordFailure(long id, RuntimeException exception) {
        List<Integer> attempts = jdbc.queryForList("""
                SELECT attempt_count FROM bf_order_notification_outbox
                WHERE id=? AND processed_at IS NULL AND dead_lettered_at IS NULL FOR UPDATE
                """, Integer.class, id);
        if (attempts.isEmpty()) return;
        int nextAttempt = attempts.get(0) + 1;
        String safeError = safeError(exception);
        if (nextAttempt >= properties.getMaxAttempts()) {
            jdbc.update("""
                    UPDATE bf_order_notification_outbox
                    SET attempt_count=?,last_error=?,dead_lettered_at=CURRENT_TIMESTAMP,
                        processed_at=CURRENT_TIMESTAMP
                    WHERE id=? AND processed_at IS NULL AND dead_lettered_at IS NULL
                    """, nextAttempt, "DEAD_LETTER: " + safeError, id);
            return;
        }
        jdbc.update("""
                UPDATE bf_order_notification_outbox
                SET attempt_count=?,last_error=?,next_attempt_at=?
                WHERE id=? AND processed_at IS NULL AND dead_lettered_at IS NULL
                """, nextAttempt, "RETRY: " + safeError,
                LocalDateTime.now().plus(retryDelay(nextAttempt)), id);
    }

    private Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        Duration calculated = properties.getBaseRetryDelay().multipliedBy(multiplier);
        return calculated.compareTo(Duration.ofHours(24)) > 0 ? Duration.ofHours(24) : calculated;
    }

    private String safeError(RuntimeException exception) {
        String source = exception.getClass().getSimpleName();
        String message = exception.getMessage();
        if (message != null && !message.isBlank()) source += ": " + message;
        String redacted = source
                .replaceAll("(?<!\\d)1\\d{10}(?!\\d)", "1**********")
                .replaceAll("(?i)(access[_-]?token|app[_-]?secret|token|secret|password|passwd|api[_-]?key|authorization)"
                        + "\\s*[:=]\\s*[^\\s,;]+", "$1=***");
        return redacted.substring(0, Math.min(255, redacted.length()));
    }

    private record OrderEvent(long id, long userId, String orderNo, String eventType,
                              String fromStatus, String toStatus, int attemptCount) { }
}
