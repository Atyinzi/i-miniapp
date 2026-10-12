-- 示例景区小程序联调目录：门票、军事体验项目及未来365天分时库存。

UPDATE bf_business_site
SET name='示例景区',
    address='内蒙古自治区包头市青山区兵工路',
    service_phone='0472-0000001',
    introduction='兵器文化主题景区，提供展览参观、主题门票及预约制军事体验项目。',
    status='ACTIVE'
WHERE site_code='SCENIC_WEAPON_CITY';

UPDATE bf_resource r
JOIN bf_business_site s ON s.id=r.site_id
SET r.name='示例景区',
    r.description='i某小程序特色兵器文化主题景区，支持线上购票和军事体验分时预约。',
    r.status='ACTIVE',
    r.attributes=JSON_OBJECT('openingHours','09:00-17:30','recommendedDurationMinutes',180)
WHERE s.site_code='SCENIC_WEAPON_CITY' AND r.resource_type='SCENIC_TICKET';

INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,c.sku_code,c.name,c.price_cent,'ACTIVE',c.attributes
FROM bf_resource r
JOIN bf_business_site s ON s.id=r.site_id AND s.site_code='SCENIC_WEAPON_CITY'
JOIN (
    SELECT 'SCENIC_WEAPON_ADULT_001' sku_code,'成人票' name,8000 price_cent,
           JSON_OBJECT('productKind','TICKET','ticketType','ADULT','unit','PERSON','badge','成人') attributes
    UNION ALL SELECT 'SCENIC_WEAPON_CHILD_001','儿童票',4000,
           JSON_OBJECT('productKind','TICKET','ticketType','CHILD','unit','PERSON','badge','儿童')
    UNION ALL SELECT 'SCENIC_WEAPON_FAMILY_001','亲子讲解套票',18800,
           JSON_OBJECT('productKind','TICKET','ticketType','FAMILY_BUNDLE','unit','SET','badge','套票')
    UNION ALL SELECT 'SCENIC_WEAPON_TANK_001','坦克试乘',12800,
           JSON_OBJECT('productKind','EXPERIENCE','legacyId','tank','unit','PERSON','duration','约10分钟/次','audience','仅限18-60周岁成人','description','由专业人员驾驶，全程配备安全保障，体验装甲车辆乘坐。','image','/assets/images/weapon-city/tank-ride.jpg')
    UNION ALL SELECT 'SCENIC_WEAPON_MORTAR_001','迫击炮试射',9800,
           JSON_OBJECT('productKind','EXPERIENCE','legacyId','mortar','unit','PERSON','duration','按预约时段体验','audience','仅限18-60周岁成人','description','专业教练指导，使用模拟弹药，过程安全可控。','image','/assets/images/weapon-city/mortar-trial.jpg')
    UNION ALL SELECT 'SCENIC_WEAPON_RIFLE_001','步枪模拟射击',6800,
           JSON_OBJECT('productKind','EXPERIENCE','legacyId','rifle','unit','PERSON','duration','约15分钟/次','audience','按现场安全要求参与','description','使用模拟设备，在工作人员指导下完成操作。','image','/assets/images/weapon-city/rifle-simulation.jpg')
    UNION ALL SELECT 'SCENIC_WEAPON_FITNESS_001','军事体能体验',5800,
           JSON_OBJECT('productKind','EXPERIENCE','legacyId','fitness','unit','PERSON','duration','约30分钟/次','audience','适合具备基础运动能力者','description','组合式军事体能项目，按现场教练安排完成体验。','image','/assets/images/weapon-city/military-fitness.jpg')
) c
WHERE r.resource_type='SCENIC_TICKET'
ON DUPLICATE KEY UPDATE resource_id=VALUES(resource_id),name=VALUES(name),price_cent=VALUES(price_cent),
                        status='ACTIVE',attributes=VALUES(attributes);

INSERT INTO bf_ticket_profile
(sku_id,audience_rule,usage_rule,refund_rule,entry_notice,valid_days,max_per_order,real_name_required,id_card_required)
SELECT id,
       CASE sku_code
           WHEN 'SCENIC_WEAPON_CHILD_001' THEN '适用于1.2—1.4米儿童，须由成人陪同入园'
           WHEN 'SCENIC_WEAPON_FAMILY_001' THEN '适用于家庭游客及讲解服务需求游客'
           WHEN 'SCENIC_WEAPON_ADULT_001' THEN '适用于18—60周岁成人游客'
           ELSE '须满足项目年龄、健康状况及现场安全要求'
       END,
       CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(attributes,'$.productKind'))='EXPERIENCE'
            THEN '仅限预约日期和时段使用，迟到请服从现场调度'
            ELSE '仅限预约入园日期使用，单次入园有效' END,
       CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(attributes,'$.productKind'))='EXPERIENCE'
            THEN '体验开始前1小时可申请整单退款，逾期不可退款'
            ELSE '未核销且入园日前可按平台规则申请整单退款' END,
       CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(attributes,'$.productKind'))='EXPERIENCE'
            THEN '请携带预约身份证件提前15分钟到达，服从教练和安全人员指导'
            ELSE '请携带预约时填写的有效身份证件入园' END,
       1,
       CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(attributes,'$.productKind'))='EXPERIENCE' THEN 1 ELSE 10 END,
       1,1
FROM bf_resource_sku
WHERE sku_code IN ('SCENIC_WEAPON_ADULT_001','SCENIC_WEAPON_CHILD_001','SCENIC_WEAPON_FAMILY_001',
                   'SCENIC_WEAPON_TANK_001','SCENIC_WEAPON_MORTAR_001','SCENIC_WEAPON_RIFLE_001',
                   'SCENIC_WEAPON_FITNESS_001')
ON DUPLICATE KEY UPDATE audience_rule=VALUES(audience_rule),usage_rule=VALUES(usage_rule),
refund_rule=VALUES(refund_rule),entry_notice=VALUES(entry_notice),valid_days=VALUES(valid_days),
max_per_order=VALUES(max_per_order),real_name_required=VALUES(real_name_required),id_card_required=VALUES(id_card_required);

-- 普通门票使用全天库存。
INSERT IGNORE INTO bf_inventory
(sku_id,business_date,time_slot,total_quantity,available_quantity,price_cent)
SELECT sku.id,DATE_ADD(CURRENT_DATE,INTERVAL days.day_offset DAY),'',200,200,sku.price_cent
FROM bf_resource_sku sku
CROSS JOIN (
    SELECT ones.n+tens.n+hundreds.n day_offset
    FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 10 UNION ALL SELECT 20 UNION ALL SELECT 30 UNION ALL SELECT 40
                UNION ALL SELECT 50 UNION ALL SELECT 60 UNION ALL SELECT 70 UNION ALL SELECT 80 UNION ALL SELECT 90) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 100 UNION ALL SELECT 200 UNION ALL SELECT 300) hundreds
    WHERE ones.n+tens.n+hundreds.n BETWEEN 0 AND 365
) days
WHERE sku.sku_code IN ('SCENIC_WEAPON_ADULT_001','SCENIC_WEAPON_CHILD_001','SCENIC_WEAPON_FAMILY_001');

-- 体验项目按预约时段独立扣减库存。
INSERT IGNORE INTO bf_inventory
(sku_id,business_date,time_slot,total_quantity,available_quantity,price_cent)
SELECT sku.id,DATE_ADD(CURRENT_DATE,INTERVAL days.day_offset DAY),slots.time_slot,
       CASE sku.sku_code WHEN 'SCENIC_WEAPON_TANK_001' THEN 8
                         WHEN 'SCENIC_WEAPON_MORTAR_001' THEN 6
                         WHEN 'SCENIC_WEAPON_RIFLE_001' THEN 12 ELSE 16 END,
       CASE sku.sku_code WHEN 'SCENIC_WEAPON_TANK_001' THEN 8
                         WHEN 'SCENIC_WEAPON_MORTAR_001' THEN 6
                         WHEN 'SCENIC_WEAPON_RIFLE_001' THEN 12 ELSE 16 END,
       sku.price_cent
FROM bf_resource_sku sku
CROSS JOIN (
    SELECT ones.n+tens.n+hundreds.n day_offset
    FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 10 UNION ALL SELECT 20 UNION ALL SELECT 30 UNION ALL SELECT 40
                UNION ALL SELECT 50 UNION ALL SELECT 60 UNION ALL SELECT 70 UNION ALL SELECT 80 UNION ALL SELECT 90) tens
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 100 UNION ALL SELECT 200 UNION ALL SELECT 300) hundreds
    WHERE ones.n+tens.n+hundreds.n BETWEEN 0 AND 365
) days
CROSS JOIN (
    SELECT '09:00-10:00' time_slot UNION ALL SELECT '10:30-11:30'
    UNION ALL SELECT '14:00-15:00' UNION ALL SELECT '15:30-16:30'
) slots
WHERE sku.sku_code IN ('SCENIC_WEAPON_TANK_001','SCENIC_WEAPON_MORTAR_001',
                       'SCENIC_WEAPON_RIFLE_001','SCENIC_WEAPON_FITNESS_001');
