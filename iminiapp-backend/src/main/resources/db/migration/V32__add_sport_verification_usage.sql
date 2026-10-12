-- 体育场馆阶段5：核销流水及场地使用状态。
ALTER TABLE bf_sport_booking
    ADD COLUMN checked_in_at DATETIME NULL AFTER refunded_at,
    ADD COLUMN completed_at DATETIME NULL AFTER checked_in_at,
    ADD COLUMN verification_id BIGINT NULL AFTER completed_at,
    ADD UNIQUE KEY uk_sport_booking_verification (verification_id),
    ADD CONSTRAINT fk_sport_booking_verification FOREIGN KEY (verification_id) REFERENCES bf_verification(id);

CREATE TABLE bf_sport_verification_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_id BIGINT NOT NULL,
    verification_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    verification_channel VARCHAR(32) NOT NULL,
    device_no VARCHAR(64),
    booking_status_before VARCHAR(32) NOT NULL,
    booking_status_after VARCHAR(32) NOT NULL,
    verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_verification_ticket (verification_id),
    INDEX idx_sport_verification_booking_time (booking_id,verified_at),
    INDEX idx_sport_verification_operator_time (operator_id,verified_at),
    CONSTRAINT fk_sport_verification_booking FOREIGN KEY (booking_id) REFERENCES bf_sport_booking(id),
    CONSTRAINT fk_sport_verification_ticket FOREIGN KEY (verification_id) REFERENCES bf_verification(id),
    CONSTRAINT fk_sport_verification_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场地核销流水';

UPDATE bf_verification v
JOIN bf_order_item i ON i.id=v.order_item_id
JOIN bf_sport_booking b ON b.order_item_id=i.id
SET v.valid_from=TIMESTAMP(b.booking_date,b.start_time),
    v.valid_until=TIMESTAMP(b.booking_date,b.end_time)
WHERE v.valid_from IS NULL;
