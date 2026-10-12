-- 可由管理后台维护的场馆与车队常用地点。正式坐标统一使用 GCJ-02。
CREATE TABLE bf_location_place (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    place_code VARCHAR(64) NOT NULL,
    place_type VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255),
    longitude DECIMAL(10,7),
    latitude DECIMAL(10,7),
    coordinate_system VARCHAR(16) NOT NULL DEFAULT 'GCJ-02',
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    updated_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_location_place_code (place_code),
    INDEX idx_location_place_type_status_sort (place_type,status,sort_order),
    CONSTRAINT fk_location_place_updater FOREIGN KEY (updated_by) REFERENCES bf_user(id),
    CONSTRAINT chk_location_place_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_location_place_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场馆地图与车队常用地点配置';

INSERT INTO bf_location_place(place_code,place_type,name,address,sort_order,status) VALUES
('NORTH_HOTEL','VENUE','示例宾馆','包头市青山区i某小程序园区',10,'INACTIVE'),
('WEAPON_CITY','VENUE','示例景区','内蒙古自治区包头市青山区兵工路',20,'INACTIVE'),
('HOUSHAN_LODGING','VENUE','后山住宿','正式地址待确认',30,'INACTIVE'),
('SPORTS_VENUE','VENUE','i某小程序体育馆','正式地址待确认',40,'INACTIVE'),
('BATH_VENUE','VENUE','i某小程序浴都','正式地址待确认',50,'INACTIVE')
ON DUPLICATE KEY UPDATE place_code=VALUES(place_code);
