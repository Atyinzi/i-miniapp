package com.iminiapp.backend.payment.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatExceptionalCaptureRefundServiceTests {

    @Test
    void successfulDuplicateRefundOnlyClosesRefundAndExceptionalPayment() {
        RecordingJdbc jdbc = new RecordingJdbc();
        WechatExceptionalCaptureRefundService service =
                new WechatExceptionalCaptureRefundService(jdbc);

        service.handle(91, 41, "AUTO_DUPLICATE_CAPTURE", 7,
                "PROCESSING", "SUCCESS", "successful");

        assertEquals(List.of("AUTO_REFUNDED", "DUPLICATE_REFUNDED",
                "STATUS_LOG", "AUDIT"), jdbc.operations);
        assertFalse(jdbc.sql.stream().anyMatch(value -> value.contains("UPDATE bf_order")));
        assertFalse(jdbc.sql.stream().anyMatch(value -> value.contains("bf_inventory")));
        assertFalse(jdbc.sql.stream().anyMatch(value -> value.contains("bf_verification")));
    }

    @Test
    void closedLateRefundRemainsVisibleAsFailedWithoutOrderRollback() {
        RecordingJdbc jdbc = new RecordingJdbc();
        WechatExceptionalCaptureRefundService service =
                new WechatExceptionalCaptureRefundService(jdbc);

        service.handle(92, 42, "AUTO_LATE_CAPTURE", 7,
                "PROCESSING", "CLOSED", "channel closed");

        assertEquals(List.of("FAILED", "LATE_REFUND_FAILED",
                "STATUS_LOG", "AUDIT"), jdbc.operations);
        assertTrue(jdbc.sql.stream().noneMatch(value -> value.contains("UPDATE bf_order")));
    }

    @Test
    void terminalCallbackRetryIsAStableNoOp() {
        RecordingJdbc jdbc = new RecordingJdbc();
        WechatExceptionalCaptureRefundService service =
                new WechatExceptionalCaptureRefundService(jdbc);

        service.handle(92, 42, "AUTO_LATE_CAPTURE", 7,
                "AUTO_REFUNDED", "SUCCESS", "retry");

        assertTrue(jdbc.operations.isEmpty());
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<String> operations = new ArrayList<>();
        private final List<String> sql = new ArrayList<>();

        @Override
        public int update(String statement, Object... args) {
            String normalized = statement.replaceAll("\\s+", " ").trim();
            sql.add(normalized);
            if (normalized.startsWith("UPDATE bf_refund")) {
                operations.add(normalized.contains("AUTO_REFUNDED") ? "AUTO_REFUNDED"
                        : normalized.contains("status='FAILED'") ? "FAILED" : "REFUND_NOTE");
            } else if (normalized.startsWith("UPDATE bf_payment")) {
                operations.add(String.valueOf(args[0]));
            } else if (normalized.startsWith("INSERT IGNORE INTO bf_refund_status_log")) {
                operations.add("STATUS_LOG");
            } else if (normalized.startsWith("INSERT INTO bf_audit_log")) {
                operations.add("AUDIT");
            }
            return 1;
        }
    }
}
