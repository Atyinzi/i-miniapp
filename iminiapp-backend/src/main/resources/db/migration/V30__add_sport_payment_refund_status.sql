-- 体育场馆阶段3：支付、退款状态时间及状态流水。

ALTER TABLE bf_sport_booking
    ADD COLUMN confirmed_at DATETIME NULL AFTER status,
    ADD COLUMN cancelled_at DATETIME NULL AFTER confirmed_at,
    ADD COLUMN refunded_at DATETIME NULL AFTER cancelled_at;

CREATE TABLE bf_sport_booking_status_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_id BIGINT NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    operator_id BIGINT,
    note VARCHAR(500),
    idempotency_key VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_booking_status_key (idempotency_key),
    INDEX idx_sport_booking_status_time (booking_id, created_at),
    CONSTRAINT fk_sport_booking_status_booking FOREIGN KEY (booking_id) REFERENCES bf_sport_booking(id),
    CONSTRAINT fk_sport_booking_status_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育预订状态流水';

INSERT INTO bf_sport_booking_status_log(booking_id,from_status,to_status,note,idempotency_key,created_at)
SELECT id,NULL,status,'历史预订状态初始化',CONCAT('SPORT-STATUS-INIT:',id),created_at
FROM bf_sport_booking;
