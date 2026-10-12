CREATE TABLE bf_vehicle_unavailable_period (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vehicle_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    reason VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_vehicle_unavailable_time (vehicle_id,start_at,end_at,status),
    CONSTRAINT fk_vehicle_unavailable_vehicle FOREIGN KEY (vehicle_id) REFERENCES bf_vehicle(id),
    CONSTRAINT chk_vehicle_unavailable_time CHECK (end_at > start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆维修、保养和人工锁定的不可租时段';
