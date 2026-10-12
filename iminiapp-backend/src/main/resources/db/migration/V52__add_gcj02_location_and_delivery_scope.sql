-- 地图定位第一阶段：统一存储 GCJ-02 坐标，并为宾馆内部配送预留可配置范围。
ALTER TABLE bf_business_site
    ADD COLUMN coordinate_system VARCHAR(16) NOT NULL DEFAULT 'GCJ-02' AFTER latitude;

ALTER TABLE bf_vehicle_booking
    ADD COLUMN departure_longitude DECIMAL(10,7) NULL AFTER departure,
    ADD COLUMN departure_latitude DECIMAL(10,7) NULL AFTER departure_longitude,
    ADD COLUMN destination_longitude DECIMAL(10,7) NULL AFTER destination,
    ADD COLUMN destination_latitude DECIMAL(10,7) NULL AFTER destination_longitude,
    ADD COLUMN coordinate_system VARCHAR(16) NOT NULL DEFAULT 'GCJ-02' AFTER destination_latitude;

ALTER TABLE bf_vehicle_dispatch
    ADD COLUMN coordinate_system VARCHAR(16) NOT NULL DEFAULT 'GCJ-02' AFTER current_latitude,
    ADD COLUMN location_updated_at DATETIME NULL AFTER coordinate_system;

ALTER TABLE bf_dining_store
    ADD COLUMN delivery_scope_type VARCHAR(32) NOT NULL DEFAULT 'INTERNAL_AREA' AFTER address,
    ADD COLUMN delivery_scope_config JSON NULL AFTER delivery_scope_type;

UPDATE bf_dining_store
SET delivery_scope_type='INTERNAL_AREA',
    delivery_scope_config=JSON_OBJECT(
        'areas',JSON_ARRAY('示例宾馆客房区','示例宾馆会议区','示例宾馆大堂及公共区域'),
        'roomRequired',TRUE
    )
WHERE store_code='NORTH_HOTEL_DINING';
