-- 体育场馆阶段2：临时闭场、可订时段库存及订单快照。

INSERT INTO bf_resource(resource_type,name,description,status,attributes)
SELECT 'SPORT_VENUE','体育场地预订','体育场馆按日期时段预订的统一订单资源','ACTIVE',JSON_OBJECT('systemResource',TRUE)
WHERE NOT EXISTS (SELECT 1 FROM bf_resource WHERE resource_type='SPORT_VENUE' AND name='体育场地预订');

INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
SELECT id,'SPORT_BOOKING_SLOT','体育场地时段',0,'ACTIVE',JSON_OBJECT('systemSku',TRUE)
FROM bf_resource WHERE resource_type='SPORT_VENUE' AND name='体育场地预订'
  AND NOT EXISTS (SELECT 1 FROM bf_resource_sku WHERE sku_code='SPORT_BOOKING_SLOT')
ORDER BY id LIMIT 1;

CREATE TABLE bf_sport_closure (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    court_id BIGINT NOT NULL,
    closure_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sport_closure_lookup (court_id, closure_date, status, start_time, end_time),
    CONSTRAINT fk_sport_closure_court FOREIGN KEY (court_id) REFERENCES bf_sport_court(id),
    CONSTRAINT fk_sport_closure_creator FOREIGN KEY (created_by) REFERENCES bf_user(id),
    CONSTRAINT chk_sport_closure_time CHECK (start_time < end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场地临时闭场';

CREATE TABLE bf_sport_booking (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    court_id BIGINT NOT NULL,
    booking_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    price_rule_id BIGINT,
    price_rule_name VARCHAR(120),
    price_cent BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HELD',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_booking_order (order_id),
    UNIQUE KEY uk_sport_booking_item (order_item_id),
    INDEX idx_sport_booking_court_time (court_id, booking_date, start_time, end_time),
    CONSTRAINT fk_sport_booking_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_sport_booking_item FOREIGN KEY (order_item_id) REFERENCES bf_order_item(id),
    CONSTRAINT fk_sport_booking_court FOREIGN KEY (court_id) REFERENCES bf_sport_court(id),
    CONSTRAINT fk_sport_booking_price_rule FOREIGN KEY (price_rule_id) REFERENCES bf_sport_price_rule(id),
    CONSTRAINT chk_sport_booking_time CHECK (start_time < end_time),
    CONSTRAINT chk_sport_booking_price CHECK (price_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场地预订快照';

CREATE INDEX idx_inventory_sport_slot ON bf_inventory(business_date,time_slot,available_quantity);
