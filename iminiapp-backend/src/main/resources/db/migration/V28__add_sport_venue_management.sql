-- 体育场馆阶段1：场馆、场地、每周开放时段及分层价格规则。

CREATE TABLE bf_sport_venue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    venue_code VARCHAR(50) NOT NULL,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(32),
    description TEXT,
    open_time TIME NOT NULL,
    close_time TIME NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_venue_code (venue_code),
    INDEX idx_sport_venue_status (status),
    CONSTRAINT chk_sport_venue_hours CHECK (open_time < close_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场馆';

CREATE TABLE bf_sport_court_type (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    venue_id BIGINT NOT NULL,
    type_code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    base_price_cent BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_court_type_code (venue_id, type_code),
    INDEX idx_sport_court_type_venue_status (venue_id, status),
    CONSTRAINT fk_sport_court_type_venue FOREIGN KEY (venue_id) REFERENCES bf_sport_venue(id),
    CONSTRAINT chk_sport_court_type_price CHECK (base_price_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场地类型及基础收费标准';

CREATE TABLE bf_sport_court (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    venue_id BIGINT NOT NULL,
    court_type_id BIGINT NOT NULL,
    court_code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    location VARCHAR(255),
    capacity INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_court_code (venue_id, court_code),
    INDEX idx_sport_court_type_status (court_type_id, status),
    CONSTRAINT fk_sport_court_venue FOREIGN KEY (venue_id) REFERENCES bf_sport_venue(id),
    CONSTRAINT fk_sport_court_type FOREIGN KEY (court_type_id) REFERENCES bf_sport_court_type(id),
    CONSTRAINT chk_sport_court_capacity CHECK (capacity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场地';

CREATE TABLE bf_sport_opening_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    court_id BIGINT NOT NULL,
    day_of_week TINYINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    slot_minutes INT NOT NULL DEFAULT 60,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_opening_range (court_id, day_of_week, start_time, end_time),
    INDEX idx_sport_opening_court_day (court_id, day_of_week, status),
    CONSTRAINT fk_sport_opening_court FOREIGN KEY (court_id) REFERENCES bf_sport_court(id),
    CONSTRAINT chk_sport_opening_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_sport_opening_time CHECK (start_time < end_time),
    CONSTRAINT chk_sport_opening_slot CHECK (slot_minutes BETWEEN 15 AND 240)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场地每周可预订时段规则';

CREATE TABLE bf_sport_price_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    venue_id BIGINT NOT NULL,
    court_type_id BIGINT,
    court_id BIGINT,
    rule_code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    rule_type VARCHAR(20) NOT NULL,
    start_date DATE,
    end_date DATE,
    day_of_week TINYINT,
    start_time TIME,
    end_time TIME,
    price_cent BIGINT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_price_rule_code (rule_code),
    INDEX idx_sport_price_rule_match (venue_id, court_type_id, court_id, status, priority),
    INDEX idx_sport_price_rule_date (start_date, end_date),
    CONSTRAINT fk_sport_price_venue FOREIGN KEY (venue_id) REFERENCES bf_sport_venue(id),
    CONSTRAINT fk_sport_price_type FOREIGN KEY (court_type_id) REFERENCES bf_sport_court_type(id),
    CONSTRAINT fk_sport_price_court FOREIGN KEY (court_id) REFERENCES bf_sport_court(id),
    CONSTRAINT chk_sport_price_amount CHECK (price_cent >= 0),
    CONSTRAINT chk_sport_price_day CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_sport_price_dates CHECK (start_date IS NULL OR end_date IS NULL OR start_date <= end_date),
    CONSTRAINT chk_sport_price_times CHECK (
        (start_time IS NULL AND end_time IS NULL) OR
        (start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育场地淡旺季及节假日价格规则';
