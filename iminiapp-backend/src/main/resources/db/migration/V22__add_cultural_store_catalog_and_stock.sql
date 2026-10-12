-- 文创商城第一、二阶段：分类、商品扩展资料和实体库存。

CREATE TABLE bf_cultural_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_category_code (category_code),
    INDEX idx_cultural_category_status_sort (status, sort_order),
    CONSTRAINT fk_cultural_category_parent FOREIGN KEY (parent_id) REFERENCES bf_cultural_category(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创商品分类';

CREATE TABLE bf_cultural_product_profile (
    resource_id BIGINT PRIMARY KEY,
    category_id BIGINT NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    detail_html LONGTEXT,
    market_price_cent BIGINT,
    weight_gram INT,
    sales_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_product_code (product_code),
    INDEX idx_cultural_product_category (category_id),
    CONSTRAINT fk_cultural_product_resource FOREIGN KEY (resource_id) REFERENCES bf_resource(id),
    CONSTRAINT fk_cultural_product_category FOREIGN KEY (category_id) REFERENCES bf_cultural_category(id),
    CONSTRAINT chk_cultural_market_price CHECK (market_price_cent IS NULL OR market_price_cent >= 0),
    CONSTRAINT chk_cultural_weight CHECK (weight_gram IS NULL OR weight_gram >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创商品SPU扩展资料';

CREATE TABLE bf_cultural_stock (
    sku_id BIGINT PRIMARY KEY,
    on_hand_quantity INT NOT NULL DEFAULT 0,
    locked_quantity INT NOT NULL DEFAULT 0,
    available_quantity INT NOT NULL DEFAULT 0,
    warning_threshold INT NOT NULL DEFAULT 10,
    version INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_cultural_stock_sku FOREIGN KEY (sku_id) REFERENCES bf_resource_sku(id),
    CONSTRAINT chk_cultural_stock_values CHECK (
        on_hand_quantity >= 0 AND locked_quantity >= 0 AND available_quantity >= 0
        AND warning_threshold >= 0 AND on_hand_quantity = locked_quantity + available_quantity
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创SKU实体库存';

CREATE TABLE bf_cultural_stock_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    quantity_delta INT NOT NULL,
    on_hand_after INT NOT NULL,
    available_after INT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    operator_id BIGINT,
    reference_no VARCHAR(64),
    remark VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cultural_stock_log_idempotency (idempotency_key),
    INDEX idx_cultural_stock_log_sku_time (sku_id, created_at),
    CONSTRAINT fk_cultural_stock_log_sku FOREIGN KEY (sku_id) REFERENCES bf_resource_sku(id),
    CONSTRAINT fk_cultural_stock_log_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文创库存变更流水';

CREATE INDEX idx_cultural_sku_resource_status ON bf_resource_sku(resource_id, status);
