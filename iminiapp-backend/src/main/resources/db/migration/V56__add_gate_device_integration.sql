-- 闸机设备采用逐台密钥、HMAC 签名和事件幂等；数据库不保存原始核销码。
CREATE TABLE bf_gate_device (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    device_no VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    venue_code VARCHAR(64),
    integration_mode VARCHAR(32) NOT NULL DEFAULT 'API_HMAC',
    secret_cipher BLOB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    last_seen_at DATETIME,
    created_by BIGINT,
    updated_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gate_device_no (device_no),
    INDEX idx_gate_device_status (status),
    CONSTRAINT fk_gate_device_creator FOREIGN KEY (created_by) REFERENCES bf_user(id),
    CONSTRAINT fk_gate_device_updater FOREIGN KEY (updated_by) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闸机设备及加密凭据';

CREATE TABLE bf_gate_request_nonce (
    device_id BIGINT NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, nonce),
    INDEX idx_gate_nonce_expiry (expires_at),
    CONSTRAINT fk_gate_nonce_device FOREIGN KEY (device_id) REFERENCES bf_gate_device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闸机请求防重放随机数';

CREATE TABLE bf_gate_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    device_id BIGINT NOT NULL,
    event_no VARCHAR(64) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    verification_ref BIGINT,
    order_id BIGINT,
    decision VARCHAR(20) NOT NULL,
    message VARCHAR(255) NOT NULL,
    happened_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gate_device_event (device_id, event_no),
    INDEX idx_gate_event_created (created_at),
    INDEX idx_gate_event_decision (decision, created_at),
    CONSTRAINT fk_gate_event_device FOREIGN KEY (device_id) REFERENCES bf_gate_device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闸机核销决策事件';
