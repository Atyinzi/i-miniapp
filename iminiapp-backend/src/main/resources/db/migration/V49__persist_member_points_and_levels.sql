-- 小程序通用会员资产：等级有效期、积分订单与积分流水。
ALTER TABLE bf_member_account
    ADD COLUMN valid_until DATE NULL AFTER growth_value;

INSERT INTO bf_sport_member_level
    (level_code, level_name, sport_discount_bp, min_growth_value, benefits, status)
VALUES
    ('NORMAL', '普通会员', 10000, 0, JSON_ARRAY('会员积分'), 'ACTIVE'),
    ('SILVER', '白银会员', 9500, 500, JSON_ARRAY('会员积分', '会员折扣'), 'ACTIVE'),
    ('GOLD', '黄金会员', 9000, 1500, JSON_ARRAY('会员积分', '会员折扣'), 'ACTIVE'),
    ('PLATINUM', '铂金会员', 8500, 3000, JSON_ARRAY('会员积分', '会员折扣'), 'ACTIVE'),
    ('DIAMOND', '钻石会员', 8000, 6000, JSON_ARRAY('会员积分', '会员折扣'), 'ACTIVE'),
    ('BLACK_GOLD', '黑金会员', 7000, 10000, JSON_ARRAY('会员积分', '会员折扣'), 'ACTIVE')
ON DUPLICATE KEY UPDATE
    level_name=VALUES(level_name),
    sport_discount_bp=VALUES(sport_discount_bp),
    min_growth_value=VALUES(min_growth_value),
    status='ACTIVE';

CREATE TABLE bf_member_point_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_account_id BIGINT NOT NULL,
    order_ref VARCHAR(64) NOT NULL,
    paid_amount_cent BIGINT NOT NULL DEFAULT 0,
    points INT NOT NULL,
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at DATETIME NULL,
    reversed_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_member_point_order (member_account_id, order_ref),
    INDEX idx_member_point_status_time (member_account_id, status, updated_at),
    CONSTRAINT fk_member_point_order_account FOREIGN KEY (member_account_id) REFERENCES bf_member_account(id),
    CONSTRAINT chk_member_point_order_points CHECK (points >= 0),
    CONSTRAINT chk_member_point_order_status CHECK (status IN ('PENDING', 'SETTLED', 'CANCELLED', 'REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员订单积分状态';

CREATE TABLE bf_member_point_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_account_id BIGINT NOT NULL,
    order_ref VARCHAR(64) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    points_delta INT NOT NULL,
    points_after INT NOT NULL,
    growth_after INT NOT NULL,
    description VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_member_point_transaction (member_account_id, order_ref, transaction_type),
    INDEX idx_member_point_transaction_time (member_account_id, created_at),
    CONSTRAINT fk_member_point_transaction_account FOREIGN KEY (member_account_id) REFERENCES bf_member_account(id),
    CONSTRAINT chk_member_point_after CHECK (points_after >= 0 AND growth_after >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员积分不可变流水';
