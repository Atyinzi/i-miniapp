ALTER TABLE bf_refund
    ADD COLUMN automatic_capture_payment_id BIGINT NULL AFTER payment_id,
    ADD COLUMN refund_kind VARCHAR(40) NOT NULL DEFAULT 'ORDER_REFUND' AFTER reason,
    ADD UNIQUE KEY uk_refund_automatic_capture_payment (automatic_capture_payment_id),
    ADD INDEX idx_refund_kind_status_created (refund_kind, status, created_at),
    ADD CONSTRAINT fk_refund_automatic_capture_payment
        FOREIGN KEY (automatic_capture_payment_id) REFERENCES bf_payment(id),
    ADD CONSTRAINT chk_refund_kind_capture_payment CHECK (
        (refund_kind NOT IN ('AUTO_DUPLICATE_CAPTURE', 'AUTO_LATE_CAPTURE')
            AND automatic_capture_payment_id IS NULL)
        OR
        (refund_kind IN ('AUTO_DUPLICATE_CAPTURE', 'AUTO_LATE_CAPTURE')
            AND automatic_capture_payment_id IS NOT NULL
            AND automatic_capture_payment_id = payment_id)
    );

UPDATE bf_refund
SET refund_kind = 'CULTURAL_AFTER_SALE'
WHERE id IN (
    SELECT refund_id FROM bf_cultural_after_sale WHERE refund_id IS NOT NULL
);

UPDATE bf_refund
SET idempotency_key = CONCAT('LEGACY-REFUND:', id)
WHERE idempotency_key IS NULL;

ALTER TABLE bf_refund
    MODIFY COLUMN idempotency_key VARCHAR(64) NOT NULL;

-- These indexes support reconciliation and the operations view without changing
-- the meaning of normal SUCCESS payments or normal order refunds.
ALTER TABLE bf_payment
    ADD INDEX idx_payment_transaction_status (transaction_id, status);
