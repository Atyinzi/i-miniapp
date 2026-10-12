-- 车辆租赁交还车、押金与结算账本。
CREATE TABLE bf_vehicle_handover (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dispatch_id BIGINT NOT NULL,
    delivery_at DATETIME,
    delivery_mileage_km DECIMAL(12,2),
    delivery_condition VARCHAR(500),
    delivery_photos JSON,
    returned_at DATETIME,
    return_mileage_km DECIMAL(12,2),
    return_condition VARCHAR(500),
    return_photos JSON,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING_DELIVERY',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vehicle_handover_dispatch (dispatch_id),
    CONSTRAINT fk_vehicle_handover_dispatch FOREIGN KEY (dispatch_id) REFERENCES bf_vehicle_dispatch(id),
    CONSTRAINT chk_vehicle_handover_mileage CHECK (
        (delivery_mileage_km IS NULL OR delivery_mileage_km >= 0) AND
        (return_mileage_km IS NULL OR return_mileage_km >= 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社会租赁车辆交付和归还记录';

CREATE TABLE bf_vehicle_deposit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    deposit_amount_cent BIGINT NOT NULL,
    deducted_amount_cent BIGINT NOT NULL DEFAULT 0,
    refunded_amount_cent BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'HELD',
    held_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    refunded_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vehicle_deposit_order (order_id),
    CONSTRAINT fk_vehicle_deposit_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT chk_vehicle_deposit_amount CHECK (
        deposit_amount_cent >= 0 AND deducted_amount_cent >= 0 AND refunded_amount_cent >= 0 AND
        deducted_amount_cent + refunded_amount_cent <= deposit_amount_cent
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社会租赁押金收取、扣除与退还';

CREATE TABLE bf_vehicle_settlement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    dispatch_id BIGINT NOT NULL,
    planned_return_at DATETIME NOT NULL,
    actual_return_at DATETIME NOT NULL,
    actual_distance_km DECIMAL(12,2) NOT NULL DEFAULT 0,
    overtime_minutes INT NOT NULL DEFAULT 0,
    over_distance_km DECIMAL(12,2) NOT NULL DEFAULT 0,
    overtime_amount_cent BIGINT NOT NULL DEFAULT 0,
    over_distance_amount_cent BIGINT NOT NULL DEFAULT 0,
    deposit_deduction_cent BIGINT NOT NULL DEFAULT 0,
    deposit_refund_cent BIGINT NOT NULL DEFAULT 0,
    supplement_amount_cent BIGINT NOT NULL DEFAULT 0,
    supplement_paid_cent BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    settled_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vehicle_settlement_order (order_id),
    UNIQUE KEY uk_vehicle_settlement_dispatch (dispatch_id),
    INDEX idx_vehicle_settlement_status_created (status, created_at),
    CONSTRAINT fk_vehicle_settlement_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_vehicle_settlement_dispatch FOREIGN KEY (dispatch_id) REFERENCES bf_vehicle_dispatch(id),
    CONSTRAINT chk_vehicle_settlement_amount CHECK (
        actual_distance_km >= 0 AND overtime_minutes >= 0 AND over_distance_km >= 0 AND
        overtime_amount_cent >= 0 AND over_distance_amount_cent >= 0 AND
        deposit_deduction_cent >= 0 AND deposit_refund_cent >= 0 AND
        supplement_amount_cent >= 0 AND supplement_paid_cent >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社会租赁超时、超公里、押金扣退和补款结算';

CREATE INDEX idx_vehicle_trip_event_time ON bf_vehicle_trip_log(event_type,event_at);
