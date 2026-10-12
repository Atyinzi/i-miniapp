package com.iminiapp.backend.notification.application;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationOutboxRetryMigrationTests {
    @Test
    void migrationAddsDueTimeAndTerminalDeadLetterState() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V63__add_notification_outbox_retry_state.sql"));

        assertTrue(sql.contains("next_attempt_at"));
        assertTrue(sql.contains("dead_lettered_at"));
        assertTrue(sql.contains("idx_order_notification_retry"));
    }
}
