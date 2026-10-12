-- 车辆租赁与调度第一阶段：内部用车申请的演示资源、车辆和司机。
-- 内部用车不需要支付，因此使用零金额的内部用车服务 SKU 承载统一订单明细。
INSERT INTO bf_resource(resource_type,name,description,status,attributes)
SELECT 'VEHICLE_SERVICE','内部用车申请','用于内部审批与派车的非支付服务','ACTIVE',JSON_OBJECT('businessMode','INTERNAL')
WHERE NOT EXISTS (SELECT 1 FROM bf_resource WHERE resource_type='VEHICLE_SERVICE' AND name='内部用车申请');

INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,'VEHICLE_INTERNAL_APPLICATION','内部用车申请',0,'ACTIVE',JSON_OBJECT('businessMode','INTERNAL')
FROM bf_resource r
WHERE r.resource_type='VEHICLE_SERVICE' AND r.name='内部用车申请'
  AND NOT EXISTS (SELECT 1 FROM bf_resource_sku WHERE sku_code='VEHICLE_INTERNAL_APPLICATION');

INSERT INTO bf_vehicle(model_id,vehicle_no,plate_no,status,mileage_km)
SELECT m.id,'BFH-CAR-001','蒙B·A1001','AVAILABLE',12850.50 FROM bf_vehicle_model m
WHERE m.model_code='SEDAN' AND NOT EXISTS (SELECT 1 FROM bf_vehicle WHERE vehicle_no='BFH-CAR-001');
INSERT INTO bf_vehicle(model_id,vehicle_no,plate_no,status,mileage_km)
SELECT m.id,'BFH-CAR-002','蒙B·A1002','AVAILABLE',21600.00 FROM bf_vehicle_model m
WHERE m.model_code='GL8' AND NOT EXISTS (SELECT 1 FROM bf_vehicle WHERE vehicle_no='BFH-CAR-002');
INSERT INTO bf_vehicle(model_id,vehicle_no,plate_no,status,mileage_km)
SELECT m.id,'BFH-CAR-003','蒙B·A1003','AVAILABLE',36240.80 FROM bf_vehicle_model m
WHERE m.model_code='COASTER' AND NOT EXISTS (SELECT 1 FROM bf_vehicle WHERE vehicle_no='BFH-CAR-003');

INSERT INTO bf_user(wechat_openid,nickname,status) VALUES
('driver:BFH-001','演示司机张师傅','ACTIVE'),
('driver:BFH-002','演示司机李师傅','ACTIVE')
ON DUPLICATE KEY UPDATE nickname=VALUES(nickname),status='ACTIVE';

INSERT INTO bf_driver(user_id,driver_no,name_cipher,mobile_cipher,license_no_cipher,license_no_hash,status)
SELECT u.id,'BFH-DRIVER-001',_utf8mb4'演示司机张师傅',_utf8mb4'13800000001',_utf8mb4'演示驾照001',SHA2('BFH-DRIVER-001',256),'AVAILABLE'
FROM bf_user u WHERE u.wechat_openid='driver:BFH-001'
  AND NOT EXISTS (SELECT 1 FROM bf_driver WHERE driver_no='BFH-DRIVER-001');
INSERT INTO bf_driver(user_id,driver_no,name_cipher,mobile_cipher,license_no_cipher,license_no_hash,status)
SELECT u.id,'BFH-DRIVER-002',_utf8mb4'演示司机李师傅',_utf8mb4'13800000002',_utf8mb4'演示驾照002',SHA2('BFH-DRIVER-002',256),'AVAILABLE'
FROM bf_user u WHERE u.wechat_openid='driver:BFH-002'
  AND NOT EXISTS (SELECT 1 FROM bf_driver WHERE driver_no='BFH-DRIVER-002');

CREATE TABLE bf_vehicle_application_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_item_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    decision VARCHAR(20) NOT NULL,
    comment VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vehicle_application_review_item (order_item_id),
    CONSTRAINT fk_vehicle_application_review_item FOREIGN KEY (order_item_id) REFERENCES bf_order_item(id),
    CONSTRAINT fk_vehicle_application_review_user FOREIGN KEY (reviewer_id) REFERENCES bf_user(id),
    CONSTRAINT chk_vehicle_application_review_decision CHECK (decision IN ('APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部用车审批记录';
