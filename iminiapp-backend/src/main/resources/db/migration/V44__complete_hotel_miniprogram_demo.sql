-- 兼容从 baseline v7 起步、未执行 V4 站点种子的数据库。
INSERT INTO bf_business_site (site_code,name,site_type,address,service_phone,status)
SELECT 'NORTH_HOTEL','示例宾馆','HOTEL','包头市青山区i某小程序园区','400-000-0000','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM bf_business_site WHERE site_code='NORTH_HOTEL');

INSERT INTO bf_resource (site_id,resource_type,category_code,name,description,cover_url,status,attributes)
SELECT s.id,'HOTEL_ROOM',v.category_code,v.resource_name,v.description,v.cover_url,'ACTIVE',JSON_OBJECT('capacity',2)
FROM bf_business_site s
JOIN (
  SELECT 'ELEGANT_KING' category_code,'示例宾馆雅致大床房' resource_name,'1张大床，适合商务出行。' description,'/assets/images/rooms/elegant-king-main.jpg' cover_url
  UNION ALL SELECT 'INDUSTRIAL_TWIN','示例宾馆工业风双床房','2张单床，工业文化主题客房。','/assets/images/rooms/industrial-twin-main.jpg'
  UNION ALL SELECT 'THEME_SUITE','示例宾馆i某小程序主题套房','i某小程序工业文化主题套房。','/assets/images/rooms/theme-suite-main.jpg'
) v
WHERE s.site_code='NORTH_HOTEL'
AND NOT EXISTS (SELECT 1 FROM bf_resource r WHERE r.resource_type='HOTEL_ROOM' AND r.name=v.resource_name);

INSERT INTO bf_resource_sku (resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,v.sku_code,v.sku_name,v.price_cent,'ACTIVE',JSON_OBJECT('unit','NIGHT')
FROM (
  SELECT '示例宾馆雅致大床房' resource_name,'HOTEL_ELEGANT_KING' sku_code,'雅致大床房一晚' sku_name,36800 price_cent
  UNION ALL SELECT '示例宾馆工业风双床房','HOTEL_INDUSTRIAL_TWIN','工业风双床房一晚',42800
  UNION ALL SELECT '示例宾馆i某小程序主题套房','HOTEL_THEME_SUITE','i某小程序主题套房一晚',68800
) v JOIN bf_resource r ON r.name=v.resource_name AND r.resource_type='HOTEL_ROOM'
WHERE NOT EXISTS (SELECT 1 FROM bf_resource_sku s WHERE s.sku_code=v.sku_code);

INSERT INTO bf_hotel_room_type
(hotel_id,resource_id,sku_id,type_code,name,bed_type,area_sqm,max_guests,total_rooms,low_stock_threshold,base_price_cent,description,cover_url,status)
SELECT h.id,r.id,s.id,v.type_code,v.room_name,v.bed_type,v.area_sqm,2,v.total_rooms,v.low_stock,v.price_cent,
       v.description,v.cover_url,'ACTIVE'
FROM (
  SELECT 'ELEGANT_KING' type_code,'示例宾馆雅致大床房' resource_name,'HOTEL_ELEGANT_KING' sku_code,'雅致大床房' room_name,'1张大床' bed_type,35 area_sqm,12 total_rooms,2 low_stock,36800 price_cent,'安静舒适的商务大床房。' description,'/assets/images/rooms/elegant-king-main.jpg' cover_url
  UNION ALL SELECT 'INDUSTRIAL_TWIN','示例宾馆工业风双床房','HOTEL_INDUSTRIAL_TWIN','工业风双床房','2张单床',42,10,2,42800,'融合i某小程序工业文化元素的双床房。','/assets/images/rooms/industrial-twin-main.jpg'
  UNION ALL SELECT 'THEME_SUITE','示例宾馆i某小程序主题套房','HOTEL_THEME_SUITE','i某小程序主题套房','1张大床',58,6,1,68800,'适合深度体验i某小程序工业文化的主题套房。','/assets/images/rooms/theme-suite-main.jpg'
) v
JOIN bf_hotel h ON h.hotel_code='NORTH_HOTEL'
JOIN bf_resource r ON r.name=v.resource_name AND r.resource_type='HOTEL_ROOM'
JOIN bf_resource_sku s ON s.resource_id=r.id AND s.sku_code=v.sku_code
WHERE NOT EXISTS (SELECT 1 FROM bf_hotel_room_type rt WHERE rt.hotel_id=h.id AND rt.type_code=v.type_code);

INSERT IGNORE INTO bf_hotel_facility (room_type_id,facility_code,name,sort_order)
SELECT id,'WIFI','无线网络',1 FROM bf_hotel_room_type
WHERE hotel_id=(SELECT id FROM bf_hotel WHERE hotel_code='NORTH_HOTEL');
INSERT IGNORE INTO bf_hotel_facility (room_type_id,facility_code,name,sort_order)
SELECT id,'BREAKFAST','双人早餐',2 FROM bf_hotel_room_type
WHERE hotel_id=(SELECT id FROM bf_hotel WHERE hotel_code='NORTH_HOTEL');

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
