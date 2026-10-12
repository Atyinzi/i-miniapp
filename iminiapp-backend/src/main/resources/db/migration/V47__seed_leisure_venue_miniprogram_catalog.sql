-- 小程序休闲场馆联调目录：浴都、体育馆及未来可生成库存的开放时段规则。

INSERT INTO bf_sport_venue(venue_code,name,address,contact_phone,description,open_time,close_time,status) VALUES
('IMINIAPP_BATH','i某小程序浴都','i某小程序园区浴都','400-000-2001','基础洗浴服务，按两小时时段预约入场','09:00:00','21:00:00','ACTIVE'),
('IMINIAPP_SPORTS','i某小程序体育馆','i某小程序园区体育馆','400-000-2002','篮球、羽毛球等指定运动场地，按两小时时段预约','08:00:00','22:00:00','ACTIVE');

INSERT INTO bf_sport_court_type(venue_id,type_code,name,description,base_price_cent,status)
SELECT id,'BATH_ENTRY','浴都入场','基础洗浴与休闲区域单次入场',5800,'ACTIVE'
FROM bf_sport_venue WHERE venue_code='IMINIAPP_BATH';
INSERT INTO bf_sport_court_type(venue_id,type_code,name,description,base_price_cent,status)
SELECT id,'SPORTS_ENTRY','综合运动场','篮球、羽毛球等指定运动区域',8000,'ACTIVE'
FROM bf_sport_venue WHERE venue_code='IMINIAPP_SPORTS';

INSERT INTO bf_sport_court(venue_id,court_type_id,court_code,name,location,capacity,status)
SELECT v.id,t.id,'BATH_ENTRY_01','浴都预约入场','浴都一层接待处',1,'ACTIVE'
FROM bf_sport_venue v JOIN bf_sport_court_type t ON t.venue_id=v.id AND t.type_code='BATH_ENTRY'
WHERE v.venue_code='IMINIAPP_BATH';
INSERT INTO bf_sport_court(venue_id,court_type_id,court_code,name,location,capacity,status)
SELECT v.id,t.id,'SPORTS_ENTRY_01','体育馆综合场地','体育馆主场区',1,'ACTIVE'
FROM bf_sport_venue v JOIN bf_sport_court_type t ON t.venue_id=v.id AND t.type_code='SPORTS_ENTRY'
WHERE v.venue_code='IMINIAPP_SPORTS';

INSERT INTO bf_sport_opening_rule(court_id,day_of_week,start_time,end_time,slot_minutes,status)
SELECT c.id,d.day_of_week,'09:00:00','21:00:00',120,'ACTIVE'
FROM bf_sport_court c JOIN bf_sport_venue v ON v.id=c.venue_id
CROSS JOIN (
    SELECT 1 day_of_week UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
) d WHERE v.venue_code='IMINIAPP_BATH';

INSERT INTO bf_sport_opening_rule(court_id,day_of_week,start_time,end_time,slot_minutes,status)
SELECT c.id,d.day_of_week,'08:00:00','22:00:00',120,'ACTIVE'
FROM bf_sport_court c JOIN bf_sport_venue v ON v.id=c.venue_id
CROSS JOIN (
    SELECT 1 day_of_week UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
) d WHERE v.venue_code='IMINIAPP_SPORTS';
