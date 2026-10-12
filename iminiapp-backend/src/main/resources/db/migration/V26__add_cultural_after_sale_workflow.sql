-- 文创商城退款与退货售后。
CREATE TABLE bf_cultural_after_sale (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_no VARCHAR(32) NOT NULL,
    order_id BIGINT NOT NULL,
    after_sale_type VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    evidence_urls JSON NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    refund_amount_cent BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    reviewed_by BIGINT NULL,
    review_comment VARCHAR(500) NULL,
    reviewed_at DATETIME NULL,
    return_address VARCHAR(500) NULL,
    refund_id BIGINT NULL,
    previous_order_status VARCHAR(32) NULL,
    completed_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_after_sale_no (after_sale_no),
    UNIQUE KEY uk_cultural_after_sale_request (requested_by, idempotency_key),
    UNIQUE KEY uk_cultural_after_sale_refund (refund_id),
    INDEX idx_cultural_after_sale_order_status (order_id, status),
    INDEX idx_cultural_after_sale_status_time (status, created_at),
    CONSTRAINT fk_cultural_after_sale_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_cultural_after_sale_requester FOREIGN KEY (requested_by) REFERENCES bf_user(id),
    CONSTRAINT fk_cultural_after_sale_reviewer FOREIGN KEY (reviewed_by) REFERENCES bf_user(id),
    CONSTRAINT fk_cultural_after_sale_refund FOREIGN KEY (refund_id) REFERENCES bf_refund(id),
    CONSTRAINT chk_cultural_after_sale_amount CHECK (refund_amount_cent > 0),
    CONSTRAINT chk_cultural_after_sale_type CHECK (after_sale_type IN ('REFUND_ONLY','RETURN_REFUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创商城售后单';

CREATE TABLE bf_cultural_after_sale_return (
    after_sale_id BIGINT PRIMARY KEY,
    carrier_code VARCHAR(32) NOT NULL,
    carrier_name VARCHAR(64) NOT NULL,
    tracking_no VARCHAR(64) NOT NULL,
    shipped_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_at DATETIME NULL,
    received_by BIGINT NULL,
    remark VARCHAR(255) NULL,
    UNIQUE KEY uk_cultural_return_waybill (carrier_code, tracking_no),
    CONSTRAINT fk_cultural_return_after_sale FOREIGN KEY (after_sale_id) REFERENCES bf_cultural_after_sale(id),
    CONSTRAINT fk_cultural_return_receiver FOREIGN KEY (received_by) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创售后退货物流';

CREATE TABLE bf_cultural_after_sale_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    operator_id BIGINT NULL,
    note VARCHAR(500) NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_after_sale_log_key (idempotency_key),
    INDEX idx_cultural_after_sale_log_time (after_sale_id, created_at),
    CONSTRAINT fk_cultural_after_sale_log_case FOREIGN KEY (after_sale_id) REFERENCES bf_cultural_after_sale(id),
    CONSTRAINT fk_cultural_after_sale_log_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创售后状态流水';
