-- Keep transient notification-enqueue failures retryable and make exhausted rows auditable.
ALTER TABLE bf_order_notification_outbox
    ADD COLUMN next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER attempt_count,
    ADD COLUMN dead_lettered_at DATETIME NULL AFTER last_error,
    ADD INDEX idx_order_notification_retry
        (processed_at, dead_lettered_at, next_attempt_at, id);
