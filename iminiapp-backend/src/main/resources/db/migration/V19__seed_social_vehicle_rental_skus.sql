-- 社会租赁按车型销售；SKU 仅承载下单快照，实际派车仍由调度环节选择具体车辆。
INSERT INTO bf_resource(resource_type,name,description,status,attributes)
SELECT 'VEHICLE_RENTAL',CONCAT('社会租赁-',m.name),'社会租赁车型', 'ACTIVE', JSON_OBJECT('modelCode',m.model_code)
FROM bf_vehicle_model m
WHERE NOT EXISTS (
    SELECT 1 FROM bf_resource r WHERE r.resource_type='VEHICLE_RENTAL' AND r.name=CONCAT('社会租赁-',m.name)
);

INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,CONCAT('VEHICLE_RENTAL_',m.model_code),CONCAT(m.name,'社会租赁'),
       COALESCE(t.half_day_cent,t.full_day_cent,t.month_cent,t.bare_month_cent,0),'ACTIVE',JSON_OBJECT('modelId',m.id)
FROM bf_vehicle_model m
JOIN bf_resource r ON r.resource_type='VEHICLE_RENTAL' AND r.name=CONCAT('社会租赁-',m.name)
JOIN bf_vehicle_tariff t ON t.model_id=m.id AND t.enabled=1 AND t.effective_from<=CURRENT_DATE
    AND (t.effective_to IS NULL OR t.effective_to>=CURRENT_DATE)
WHERE NOT EXISTS (SELECT 1 FROM bf_resource_sku s WHERE s.sku_code=CONCAT('VEHICLE_RENTAL_',m.model_code));

INSERT INTO bf_platform_setting(setting_key,setting_value,value_type,is_public,description)
VALUES ('vehicle.social_rental.default_deposit_cent','100000','INTEGER',0,'社会租赁默认押金（分），上线前由运营配置')
ON DUPLICATE KEY UPDATE description=VALUES(description);
