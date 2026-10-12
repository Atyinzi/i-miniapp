ALTER TABLE bf_hotel_reservation
    ADD COLUMN stay_status VARCHAR(24) NOT NULL DEFAULT 'BOOKED' AFTER superseded_by_reservation_id,
    ADD COLUMN actual_check_in_at DATETIME AFTER stay_status,
    ADD COLUMN actual_check_out_at DATETIME AFTER actual_check_in_at,
    ADD COLUMN checked_in_by BIGINT AFTER actual_check_out_at,
    ADD COLUMN checked_out_by BIGINT AFTER checked_in_by,
    ADD COLUMN no_show_at DATETIME AFTER checked_out_by,
    ADD COLUMN contact_name_hash CHAR(64) AFTER no_show_at,
    ADD COLUMN contact_mobile_hash CHAR(64) AFTER contact_name_hash,
    ADD INDEX idx_hotel_reservation_stay_status (stay_status, check_in_date, check_out_date),
    ADD INDEX idx_hotel_reservation_contact_name (contact_name_hash),
    ADD INDEX idx_hotel_reservation_contact_mobile (contact_mobile_hash),
    ADD CONSTRAINT fk_hotel_reservation_checkin_operator FOREIGN KEY (checked_in_by) REFERENCES bf_user(id),
    ADD CONSTRAINT fk_hotel_reservation_checkout_operator FOREIGN KEY (checked_out_by) REFERENCES bf_user(id);

CREATE TABLE bf_hotel_checkin_credential (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL UNIQUE,
    code_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from DATETIME NOT NULL,
    valid_until DATETIME NOT NULL,
    used_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_hotel_checkin_credential_reservation FOREIGN KEY (reservation_id) REFERENCES bf_hotel_reservation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住宿一次性入住凭证';

CREATE TABLE bf_hotel_room_assignment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    assignment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    assigned_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_until DATETIME,
    assigned_by BIGINT NOT NULL,
    change_reason VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_hotel_assignment_reservation (reservation_id, assignment_status),
    INDEX idx_hotel_assignment_room (room_id, assignment_status),
    CONSTRAINT fk_hotel_assignment_reservation FOREIGN KEY (reservation_id) REFERENCES bf_hotel_reservation(id),
    CONSTRAINT fk_hotel_assignment_room FOREIGN KEY (room_id) REFERENCES bf_hotel_room(id),
    CONSTRAINT fk_hotel_assignment_operator FOREIGN KEY (assigned_by) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住宿房间分配及换房记录';

CREATE TABLE bf_hotel_charge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    charge_type VARCHAR(30) NOT NULL,
    description VARCHAR(200) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price_cent BIGINT NOT NULL,
    amount_cent BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_hotel_charge_reservation (reservation_id, created_at),
    CONSTRAINT fk_hotel_charge_reservation FOREIGN KEY (reservation_id) REFERENCES bf_hotel_reservation(id),
    CONSTRAINT fk_hotel_charge_operator FOREIGN KEY (created_by) REFERENCES bf_user(id),
    CHECK (quantity > 0),
    CHECK (unit_price_cent >= 0 AND amount_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住宿退房附加费用';

CREATE TABLE bf_hotel_settlement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    settlement_no VARCHAR(40) NOT NULL UNIQUE,
    reservation_id BIGINT NOT NULL UNIQUE,
    room_amount_cent BIGINT NOT NULL,
    extra_charge_cent BIGINT NOT NULL,
    order_paid_cent BIGINT NOT NULL,
    additional_paid_cent BIGINT NOT NULL DEFAULT 0,
    refund_cent BIGINT NOT NULL DEFAULT 0,
    final_amount_cent BIGINT NOT NULL,
    payment_method VARCHAR(30),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    settled_by BIGINT,
    settled_at DATETIME,
    remark VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_hotel_settlement_reservation FOREIGN KEY (reservation_id) REFERENCES bf_hotel_reservation(id),
    CONSTRAINT fk_hotel_settlement_operator FOREIGN KEY (settled_by) REFERENCES bf_user(id),
    CHECK (room_amount_cent >= 0 AND extra_charge_cent >= 0 AND order_paid_cent >= 0),
    CHECK (additional_paid_cent >= 0 AND refund_cent >= 0 AND final_amount_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住宿退房结算单';

CREATE TABLE bf_hotel_operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    status_before VARCHAR(24),
    status_after VARCHAR(24),
    operator_id BIGINT NOT NULL,
    detail JSON,
    idempotency_key VARCHAR(80),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hotel_operation_idempotency (idempotency_key),
    INDEX idx_hotel_operation_reservation (reservation_id, created_at),
    CONSTRAINT fk_hotel_operation_reservation FOREIGN KEY (reservation_id) REFERENCES bf_hotel_reservation(id),
    CONSTRAINT fk_hotel_operation_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住宿入住退房操作日志';
