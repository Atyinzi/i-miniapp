-- 小程序住宿联调基础数据。使用稳定编码，正式数据可由管理端继续维护。
INSERT INTO bf_hotel
(hotel_code,name,address,contact_phone,description,check_in_time,check_out_time,status)
SELECT 'NORTH_HOTEL','示例宾馆','包头市青山区i某小程序园区','400-000-0000',
       'i某小程序指定接待酒店，提供住宿、会议与餐饮服务。','14:00:00','12:00:00','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM bf_hotel WHERE hotel_code='NORTH_HOTEL');

INSERT INTO bf_resource (site_id,resource_type,category_code,name,description,cover_url,status,attributes)
SELECT s.id,'HOTEL_ROOM','ELEGANT_KING','示例宾馆雅致大床房','1张大床，适合商务出行。',
       '/assets/images/rooms/elegant-king-main.jpg','ACTIVE',JSON_OBJECT('capacity',2)
FROM bf_business_site s WHERE s.site_code='NORTH_HOTEL'
AND NOT EXISTS (SELECT 1 FROM bf_resource WHERE resource_type='HOTEL_ROOM' AND name='示例宾馆雅致大床房');

INSERT INTO bf_resource (site_id,resource_type,category_code,name,description,cover_url,status,attributes)
SELECT s.id,'HOTEL_ROOM','INDUSTRIAL_TWIN','示例宾馆工业风双床房','2张单床，工业文化主题客房。',
       '/assets/images/rooms/industrial-twin-main.jpg','ACTIVE',JSON_OBJECT('capacity',2)
FROM bf_business_site s WHERE s.site_code='NORTH_HOTEL'
AND NOT EXISTS (SELECT 1 FROM bf_resource WHERE resource_type='HOTEL_ROOM' AND name='示例宾馆工业风双床房');

INSERT INTO bf_resource (site_id,resource_type,category_code,name,description,cover_url,status,attributes)
SELECT s.id,'HOTEL_ROOM','THEME_SUITE','示例宾馆i某小程序主题套房','i某小程序工业文化主题套房。',
       '/assets/images/rooms/theme-suite-main.jpg','ACTIVE',JSON_OBJECT('capacity',2)
FROM bf_business_site s WHERE s.site_code='NORTH_HOTEL'
AND NOT EXISTS (SELECT 1 FROM bf_resource WHERE resource_type='HOTEL_ROOM' AND name='示例宾馆i某小程序主题套房');

INSERT INTO bf_resource_sku (resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,'HOTEL_ELEGANT_KING','雅致大床房一晚',36800,'ACTIVE',JSON_OBJECT('unit','NIGHT')
FROM bf_resource r WHERE r.name='示例宾馆雅致大床房'
AND NOT EXISTS (SELECT 1 FROM bf_resource_sku WHERE sku_code='HOTEL_ELEGANT_KING');

INSERT INTO bf_resource_sku (resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,'HOTEL_INDUSTRIAL_TWIN','工业风双床房一晚',42800,'ACTIVE',JSON_OBJECT('unit','NIGHT')
FROM bf_resource r WHERE r.name='示例宾馆工业风双床房'
AND NOT EXISTS (SELECT 1 FROM bf_resource_sku WHERE sku_code='HOTEL_INDUSTRIAL_TWIN');

INSERT INTO bf_resource_sku (resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,'HOTEL_THEME_SUITE','i某小程序主题套房一晚',68800,'ACTIVE',JSON_OBJECT('unit','NIGHT')
FROM bf_resource r WHERE r.name='示例宾馆i某小程序主题套房'
AND NOT EXISTS (SELECT 1 FROM bf_resource_sku WHERE sku_code='HOTEL_THEME_SUITE');

INSERT INTO bf_hotel_room_type
(hotel_id,resource_id,sku_id,type_code,name,bed_type,area_sqm,max_guests,total_rooms,low_stock_threshold,base_price_cent,description,cover_url,status)
SELECT h.id,r.id,s.id,'ELEGANT_KING','雅致大床房','1张大床',35,2,12,2,36800,
       '安静舒适的商务大床房。','/assets/images/rooms/elegant-king-main.jpg','ACTIVE'
FROM bf_hotel h JOIN bf_resource r ON r.name='示例宾馆雅致大床房'
JOIN bf_resource_sku s ON s.resource_id=r.id AND s.sku_code='HOTEL_ELEGANT_KING'
WHERE h.hotel_code='NORTH_HOTEL'
AND NOT EXISTS (SELECT 1 FROM bf_hotel_room_type WHERE hotel_id=h.id AND type_code='ELEGANT_KING');

INSERT INTO bf_hotel_room_type
(hotel_id,resource_id,sku_id,type_code,name,bed_type,area_sqm,max_guests,total_rooms,low_stock_threshold,base_price_cent,description,cover_url,status)
SELECT h.id,r.id,s.id,'INDUSTRIAL_TWIN','工业风双床房','2张单床',42,2,10,2,42800,
       '融合i某小程序工业文化元素的双床房。','/assets/images/rooms/industrial-twin-main.jpg','ACTIVE'
FROM bf_hotel h JOIN bf_resource r ON r.name='示例宾馆工业风双床房'
JOIN bf_resource_sku s ON s.resource_id=r.id AND s.sku_code='HOTEL_INDUSTRIAL_TWIN'
WHERE h.hotel_code='NORTH_HOTEL'
AND NOT EXISTS (SELECT 1 FROM bf_hotel_room_type WHERE hotel_id=h.id AND type_code='INDUSTRIAL_TWIN');

INSERT INTO bf_hotel_room_type
(hotel_id,resource_id,sku_id,type_code,name,bed_type,area_sqm,max_guests,total_rooms,low_stock_threshold,base_price_cent,description,cover_url,status)
SELECT h.id,r.id,s.id,'THEME_SUITE','i某小程序主题套房','1张大床',58,2,6,1,68800,
       '适合深度体验i某小程序工业文化的主题套房。','/assets/images/rooms/theme-suite-main.jpg','ACTIVE'
FROM bf_hotel h JOIN bf_resource r ON r.name='示例宾馆i某小程序主题套房'
JOIN bf_resource_sku s ON s.resource_id=r.id AND s.sku_code='HOTEL_THEME_SUITE'
WHERE h.hotel_code='NORTH_HOTEL'
AND NOT EXISTS (SELECT 1 FROM bf_hotel_room_type WHERE hotel_id=h.id AND type_code='THEME_SUITE');

INSERT IGNORE INTO bf_hotel_facility (room_type_id,facility_code,name,sort_order)
SELECT id,'WIFI','无线网络',1 FROM bf_hotel_room_type WHERE hotel_id=(SELECT id FROM bf_hotel WHERE hotel_code='NORTH_HOTEL');
INSERT IGNORE INTO bf_hotel_facility (room_type_id,facility_code,name,sort_order)
SELECT id,'BREAKFAST','双人早餐',2 FROM bf_hotel_room_type WHERE hotel_id=(SELECT id FROM bf_hotel WHERE hotel_code='NORTH_HOTEL');

-- 生成从当天起一年库存；已有日期不会被覆盖。
INSERT IGNORE INTO bf_inventory (sku_id,business_date,time_slot,total_quantity,available_quantity,price_cent)
SELECT rt.sku_id,DATE_ADD(CURRENT_DATE,INTERVAL numbers.n DAY),'',rt.total_rooms,rt.total_rooms,rt.base_price_cent
FROM bf_hotel_room_type rt
JOIN (
  SELECT ones.n + tens.n*10 + hundreds.n*100 AS n
  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) hundreds
) numbers ON numbers.n < 365
WHERE rt.hotel_id=(SELECT id FROM bf_hotel WHERE hotel_code='NORTH_HOTEL');
