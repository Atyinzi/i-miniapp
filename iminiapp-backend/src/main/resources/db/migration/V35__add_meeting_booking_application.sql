-- 会议预订阶段 3：预订申请、配套服务快照和统一订单关联。
INSERT INTO bf_resource(resource_type, name, description, status, attributes)
SELECT 'MEETING_BOOKING', '会议预订服务', '会议预订统一订单占位资源', 'ACTIVE',
       JSON_OBJECT('managedBy', 'meeting-module')
WHERE NOT EXISTS (
    SELECT 1 FROM bf_resource WHERE resource_type='MEETING_BOOKING' AND name='会议预订服务'
);

INSERT INTO bf_resource_sku(resource_id, sku_code, name, price_cent, status, attributes)
SELECT r.id, 'MEETING-BOOKING-GENERIC', '会议预订服务', 0, 'ACTIVE',
       JSON_OBJECT('priceSource', 'MEETING_PRICE_SNAPSHOT')
FROM bf_resource r
WHERE r.resource_type='MEETING_BOOKING' AND r.name='会议预订服务'
  AND NOT EXISTS (SELECT 1 FROM bf_resource_sku WHERE sku_code='MEETING-BOOKING-GENERIC')
ORDER BY r.id
LIMIT 1;

CREATE TABLE bf_meeting_reservation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_no VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    room_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    booking_type VARCHAR(20) NOT NULL,
    meeting_subject VARCHAR(200) NOT NULL,
    booking_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    attendees INT NOT NULL,
    contact_name VARCHAR(64) NOT NULL,
    contact_mobile VARCHAR(32) NOT NULL,
    remark VARCHAR(500),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    venue_price_rule_id BIGINT NOT NULL,
    venue_rule_code VARCHAR(64) NOT NULL,
    venue_rule_name VARCHAR(120) NOT NULL,
    venue_rule_priority INT NOT NULL,
    venue_amount_cent BIGINT NOT NULL,
    service_amount_cent BIGINT NOT NULL DEFAULT 0,
    total_amount_cent BIGINT NOT NULL,
    price_quoted_at DATETIME NOT NULL,
    submitted_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_meeting_reservation_no (booking_no),
    UNIQUE KEY uk_meeting_reservation_request (user_id, client_request_id),
    UNIQUE KEY uk_meeting_reservation_order (order_id),
    INDEX idx_meeting_reservation_user_status (user_id, status, created_at),
    INDEX idx_meeting_reservation_room_date (room_id, booking_date, start_time, end_time, status),
    CONSTRAINT fk_meeting_reservation_user FOREIGN KEY (user_id) REFERENCES bf_user(id),
    CONSTRAINT fk_meeting_reservation_room FOREIGN KEY (room_id) REFERENCES bf_meeting_room(id),
    CONSTRAINT fk_meeting_reservation_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_meeting_reservation_price FOREIGN KEY (venue_price_rule_id) REFERENCES bf_meeting_price_rule(id),
    CONSTRAINT chk_meeting_reservation_type CHECK (booking_type IN ('EXTERNAL', 'INTERNAL')),
    CONSTRAINT chk_meeting_reservation_status CHECK (status IN ('DRAFT', 'PENDING_PAYMENT', 'PENDING_APPROVAL', 'PAID', 'APPROVED', 'REJECTED', 'CONFIRMED', 'VERIFIED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_meeting_reservation_time CHECK (start_time < end_time),
    CONSTRAINT chk_meeting_reservation_people CHECK (attendees BETWEEN 1 AND 100000),
    CONSTRAINT chk_meeting_reservation_amount CHECK (venue_amount_cent >= 0 AND service_amount_cent >= 0 AND total_amount_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议预订申请';

CREATE TABLE bf_meeting_reservation_service (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    service_code VARCHAR(50) NOT NULL,
    service_name VARCHAR(120) NOT NULL,
    service_type VARCHAR(20) NOT NULL,
    pricing_unit VARCHAR(20) NOT NULL,
    quantity INT NOT NULL,
    unit_price_cent BIGINT NOT NULL,
    amount_cent BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_meeting_reservation_service (booking_id, service_id),
    INDEX idx_meeting_reservation_service_type (booking_id, service_type),
    CONSTRAINT fk_meeting_res_service_booking FOREIGN KEY (booking_id) REFERENCES bf_meeting_reservation(id),
    CONSTRAINT fk_meeting_res_service_item FOREIGN KEY (service_id) REFERENCES bf_meeting_service(id),
    CONSTRAINT chk_meeting_res_service_quantity CHECK (quantity BETWEEN 1 AND 100000),
    CONSTRAINT chk_meeting_res_service_amount CHECK (unit_price_cent >= 0 AND amount_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议预订配套服务快照';

CREATE TABLE bf_meeting_reservation_status_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_id BIGINT NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    operator_id BIGINT NULL,
    note VARCHAR(255),
    idempotency_key VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_meeting_res_status_key (idempotency_key),
    INDEX idx_meeting_res_status_log (booking_id, created_at),
    CONSTRAINT fk_meeting_res_log_booking FOREIGN KEY (booking_id) REFERENCES bf_meeting_reservation(id),
    CONSTRAINT fk_meeting_res_log_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议预订状态流水';
