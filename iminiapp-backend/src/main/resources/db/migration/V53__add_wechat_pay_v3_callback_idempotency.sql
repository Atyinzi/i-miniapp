CREATE TABLE bf_wechat_pay_callback_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    notification_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    business_reference VARCHAR(64) NULL,
    process_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    failure_reason VARCHAR(500) NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at DATETIME NULL,
    UNIQUE KEY uk_wechat_pay_callback_notification (notification_id),
    INDEX idx_wechat_pay_callback_status_time (process_status, received_at),
    CONSTRAINT chk_wechat_pay_callback_status
        CHECK (process_status IN ('RECEIVED', 'PROCESSED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信支付V3回调幂等与审计记录（不保存完整敏感报文）';

CREATE TABLE bf_wechat_refund_dispatch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    out_refund_no VARCHAR(64) NOT NULL,
    out_trade_no VARCHAR(32) NULL,
    transaction_id VARCHAR(128) NULL,
    refund_amount_cent BIGINT NOT NULL,
    total_amount_cent BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    reason VARCHAR(255) NULL,
    dispatch_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    channel_refund_id VARCHAR(128) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wechat_refund_dispatch_refund (refund_id),
    UNIQUE KEY uk_wechat_refund_dispatch_out_no (out_refund_no),
    INDEX idx_wechat_refund_dispatch_due (dispatch_status, next_attempt_at),
    CONSTRAINT fk_wechat_refund_dispatch_refund FOREIGN KEY (refund_id) REFERENCES bf_refund(id),
    CONSTRAINT chk_wechat_refund_dispatch_amount
        CHECK (refund_amount_cent > 0 AND total_amount_cent >= refund_amount_cent),
    CONSTRAINT chk_wechat_refund_dispatch_status
        CHECK (dispatch_status IN ('PENDING', 'SENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信支付退款可靠投递队列';
