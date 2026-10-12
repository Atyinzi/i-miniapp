CREATE TABLE bf_hotel (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hotel_code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500) NOT NULL,
    contact_phone VARCHAR(40),
    description TEXT,
    check_in_time TIME NOT NULL DEFAULT '14:00:00',
    check_out_time TIME NOT NULL DEFAULT '12:00:00',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_hotel_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='酒店门店';

CREATE TABLE bf_hotel_room_type (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hotel_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL UNIQUE,
    sku_id BIGINT NOT NULL UNIQUE,
    type_code VARCHAR(40) NOT NULL,
    name VARCHAR(200) NOT NULL,
    bed_type VARCHAR(100) NOT NULL,
    area_sqm DECIMAL(8,2),
    max_guests INT NOT NULL,
    total_rooms INT NOT NULL,
    low_stock_threshold INT NOT NULL DEFAULT 2,
    base_price_cent INT NOT NULL,
    description TEXT,
    cover_url VARCHAR(512),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hotel_room_type_code (hotel_id, type_code),
    INDEX idx_hotel_room_type_status (hotel_id, status),
    CONSTRAINT fk_hotel_room_type_hotel FOREIGN KEY (hotel_id) REFERENCES bf_hotel(id),
    CONSTRAINT fk_hotel_room_type_resource FOREIGN KEY (resource_id) REFERENCES bf_resource(id),
    CONSTRAINT fk_hotel_room_type_sku FOREIGN KEY (sku_id) REFERENCES bf_resource_sku(id),
    CHECK (max_guests > 0),
    CHECK (total_rooms >= 0),
    CHECK (low_stock_threshold >= 0),
    CHECK (base_price_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='酒店房型';

CREATE TABLE bf_hotel_room (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hotel_id BIGINT NOT NULL,
    room_type_id BIGINT NOT NULL,
    room_no VARCHAR(40) NOT NULL,
    floor_no VARCHAR(40),
    status VARCHAR(20) NOT NULL DEFAULT 'VACANT',
    remark VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hotel_room_no (hotel_id, room_no),
    INDEX idx_hotel_room_state (room_type_id, status),
    CONSTRAINT fk_hotel_room_hotel FOREIGN KEY (hotel_id) REFERENCES bf_hotel(id),
    CONSTRAINT fk_hotel_room_type FOREIGN KEY (room_type_id) REFERENCES bf_hotel_room_type(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='酒店实体房间及房态';

CREATE TABLE bf_hotel_facility (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_type_id BIGINT NOT NULL,
    facility_code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_room_type_facility (room_type_id, facility_code),
    CONSTRAINT fk_hotel_facility_room_type FOREIGN KEY (room_type_id) REFERENCES bf_hotel_room_type(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房型设施';

CREATE TABLE bf_hotel_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hotel_id BIGINT,
    room_type_id BIGINT,
    image_url VARCHAR(512) NOT NULL,
    image_type VARCHAR(30) NOT NULL DEFAULT 'GALLERY',
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_hotel_image_hotel (hotel_id, enabled, sort_order),
    INDEX idx_hotel_image_room_type (room_type_id, enabled, sort_order),
    CONSTRAINT fk_hotel_image_hotel FOREIGN KEY (hotel_id) REFERENCES bf_hotel(id),
    CONSTRAINT fk_hotel_image_room_type FOREIGN KEY (room_type_id) REFERENCES bf_hotel_room_type(id),
    CHECK ((hotel_id IS NOT NULL AND room_type_id IS NULL) OR
           (hotel_id IS NULL AND room_type_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='酒店及房型图片';

CREATE TABLE bf_hotel_price_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_type_id BIGINT NOT NULL,
    rule_code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    rule_type VARCHAR(20) NOT NULL,
    start_date DATE,
    end_date DATE,
    days_of_week VARCHAR(20),
    price_cent INT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hotel_price_rule_code (room_type_id, rule_code),
    INDEX idx_hotel_price_rule_match (room_type_id, status, start_date, end_date, priority),
    CONSTRAINT fk_hotel_price_rule_room_type FOREIGN KEY (room_type_id) REFERENCES bf_hotel_room_type(id),
    CHECK (price_cent >= 0),
    CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='酒店节假日、淡旺季及周末价格规则';

CREATE TABLE bf_hotel_inventory_source (
    inventory_id BIGINT PRIMARY KEY,
    price_rule_id BIGINT,
    source_type VARCHAR(20) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_hotel_inventory_source_inventory FOREIGN KEY (inventory_id) REFERENCES bf_inventory(id),
    CONSTRAINT fk_hotel_inventory_source_rule FOREIGN KEY (price_rule_id) REFERENCES bf_hotel_price_rule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='酒店价格日历来源';
