-- 文创商城第四阶段：自动促销、优惠券范围和订单优惠快照。

CREATE TABLE bf_cultural_promotion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    promotion_code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    promotion_type VARCHAR(32) NOT NULL,
    threshold_amount_cent BIGINT NOT NULL DEFAULT 0,
    reduction_amount_cent BIGINT,
    discount_rate DECIMAL(5,4),
    priority INT NOT NULL DEFAULT 0,
    exclusive TINYINT NOT NULL DEFAULT 0,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_promotion_code (promotion_code),
    INDEX idx_cultural_promotion_active (status, start_at, end_at, priority),
    CONSTRAINT chk_cultural_promotion_time CHECK (end_at > start_at),
    CONSTRAINT chk_cultural_promotion_values CHECK (
        threshold_amount_cent >= 0
        AND (reduction_amount_cent IS NULL OR reduction_amount_cent > 0)
        AND (discount_rate IS NULL OR (discount_rate > 0 AND discount_rate < 1))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创商城满减和折扣活动';

CREATE TABLE bf_cultural_promotion_target (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    promotion_id BIGINT NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_promotion_target (promotion_id, target_type, target_id),
    CONSTRAINT fk_cultural_promotion_target FOREIGN KEY (promotion_id) REFERENCES bf_cultural_promotion(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创促销适用分类、商品或SKU';

ALTER TABLE bf_coupon
    ADD COLUMN stackable TINYINT NOT NULL DEFAULT 0 AFTER per_user_limit,
    ADD COLUMN used_count INT NOT NULL DEFAULT 0 AFTER stackable;

CREATE TABLE bf_cultural_coupon_target (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_coupon_target (coupon_id, target_type, target_id),
    CONSTRAINT fk_cultural_coupon_target_coupon FOREIGN KEY (coupon_id) REFERENCES bf_coupon(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创优惠券适用范围';

CREATE TABLE bf_cultural_order_discount (
    order_id BIGINT PRIMARY KEY,
    promotion_id BIGINT,
    user_coupon_id BIGINT,
    promotion_discount_cent BIGINT NOT NULL DEFAULT 0,
    coupon_discount_cent BIGINT NOT NULL DEFAULT 0,
    total_discount_cent BIGINT NOT NULL DEFAULT 0,
    calculation_snapshot JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cultural_discount_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_cultural_discount_promotion FOREIGN KEY (promotion_id) REFERENCES bf_cultural_promotion(id),
    CONSTRAINT fk_cultural_discount_user_coupon FOREIGN KEY (user_coupon_id) REFERENCES bf_user_coupon(id),
    CONSTRAINT chk_cultural_discount_values CHECK (
        promotion_discount_cent >= 0 AND coupon_discount_cent >= 0 AND total_discount_cent >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创订单优惠计算快照';

CREATE INDEX idx_user_coupon_coupon_user ON bf_user_coupon(coupon_id, user_id, status);
