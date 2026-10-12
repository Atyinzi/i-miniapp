-- 会议预订阶段 6：会议核销、撤销核销和使用记录。
ALTER TABLE bf_meeting_booking
    ADD COLUMN reservation_id BIGINT NULL AFTER order_item_id,
    ADD COLUMN verification_id BIGINT NULL AFTER reservation_id,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CHECKED_IN' AFTER internal_booking,
    ADD COLUMN checked_in_by BIGINT NULL AFTER status,
    ADD UNIQUE KEY uk_meeting_usage_reservation (reservation_id),
    ADD UNIQUE KEY uk_meeting_usage_verification (verification_id),
    ADD INDEX idx_meeting_usage_status_time (status,usage_started_at),
    ADD CONSTRAINT fk_meeting_usage_reservation FOREIGN KEY (reservation_id) REFERENCES bf_meeting_reservation(id),
    ADD CONSTRAINT fk_meeting_usage_verification FOREIGN KEY (verification_id) REFERENCES bf_verification(id),
    ADD CONSTRAINT fk_meeting_usage_operator FOREIGN KEY (checked_in_by) REFERENCES bf_user(id),
    ADD CONSTRAINT chk_meeting_usage_status CHECK (status IN ('CHECKED_IN','COMPLETED','REVOKED'));

CREATE TABLE bf_meeting_verification_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    verification_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    operator_id BIGINT NOT NULL,
    verification_channel VARCHAR(32),
    device_no VARCHAR(64),
    status_before VARCHAR(24) NOT NULL,
    status_after VARCHAR(24) NOT NULL,
    note VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_meeting_verification_reservation (reservation_id,created_at),
    INDEX idx_meeting_verification_operator (operator_id,created_at),
    CONSTRAINT fk_meeting_verify_log_reservation FOREIGN KEY (reservation_id) REFERENCES bf_meeting_reservation(id),
    CONSTRAINT fk_meeting_verify_log_verification FOREIGN KEY (verification_id) REFERENCES bf_verification(id),
    CONSTRAINT fk_meeting_verify_log_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id),
    CONSTRAINT chk_meeting_verify_log_action CHECK (action IN ('CHECK_IN','REVOKE','COMPLETE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议核销与使用操作记录';
