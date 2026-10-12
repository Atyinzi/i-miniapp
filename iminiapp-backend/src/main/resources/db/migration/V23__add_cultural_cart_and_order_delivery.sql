-- 文创商城第三阶段：购物车与订单收货快照。

CREATE TABLE bf_cultural_cart_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    selected TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_cart_user_sku (user_id, sku_id),
    INDEX idx_cultural_cart_user_selected (user_id, selected, updated_at),
    CONSTRAINT fk_cultural_cart_user FOREIGN KEY (user_id) REFERENCES bf_user(id),
    CONSTRAINT fk_cultural_cart_sku FOREIGN KEY (sku_id) REFERENCES bf_resource_sku(id),
    CONSTRAINT chk_cultural_cart_quantity CHECK (quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创商城购物车';

CREATE TABLE bf_cultural_order_delivery (
    order_id BIGINT PRIMARY KEY,
    source_address_id BIGINT,
    receiver_name VARCHAR(64) NOT NULL,
    receiver_mobile VARCHAR(32) NOT NULL,
    province VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NOT NULL,
    detail_address VARCHAR(255) NOT NULL,
    postal_code VARCHAR(16),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cultural_delivery_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_cultural_delivery_address FOREIGN KEY (source_address_id) REFERENCES bf_user_address(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创订单收货地址快照';

CREATE INDEX idx_cultural_order_type_status ON bf_order(order_type, status, created_at);
