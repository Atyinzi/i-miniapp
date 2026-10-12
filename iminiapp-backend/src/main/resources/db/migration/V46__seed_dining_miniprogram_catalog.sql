-- 小程序餐饮预订联调目录：两处门店、雅间、菜品和库存。

INSERT INTO bf_dining_store(store_code,name,address,contact_phone,open_time,close_time,status) VALUES
('NORTH_HOTEL_DINING','示例宾馆餐饮','示例宾馆中餐厅','400-000-1001','06:30:00','23:00:00','ACTIVE'),
('HOUSHAN_DINING','后山住宿餐饮','后山住宿山景餐厅','400-000-1002','06:30:00','22:30:00','ACTIVE');

INSERT INTO bf_dining_table(store_id,table_code,name,table_type,capacity,status)
SELECT s.id,v.table_code,v.name,'PRIVATE_ROOM',v.capacity,'ACTIVE'
FROM (
    SELECT 'NORTH_HOTEL_DINING' store_code,'ELEGANT_4' table_code,'雅韵小雅间' name,4 capacity
    UNION ALL SELECT 'NORTH_HOTEL_DINING','IMINIAPP_6','i某小程序雅间',6
    UNION ALL SELECT 'NORTH_HOTEL_DINING','BANQUET_12','宾朋宴会雅间',12
    UNION ALL SELECT 'HOUSHAN_DINING','MOUNTAIN_4','山景小雅间',4
    UNION ALL SELECT 'HOUSHAN_DINING','FAMILY_6','山居家庭雅间',6
    UNION ALL SELECT 'HOUSHAN_DINING','BANQUET_12','山居宴会雅间',12
) v JOIN bf_dining_store s ON s.store_code=v.store_code;

INSERT INTO bf_dining_category(store_id,category_code,name,sort_order,status)
SELECT s.id,v.category_code,v.name,v.sort_order,'ACTIVE'
FROM (
    SELECT 'COLD' category_code,'凉菜' name,10 sort_order
    UNION ALL SELECT 'HOT','热菜',20
    UNION ALL SELECT 'STAPLE','主食',30
    UNION ALL SELECT 'DRINK','饮品',40
) v CROSS JOIN bf_dining_store s
WHERE s.store_code IN ('NORTH_HOTEL_DINING','HOUSHAN_DINING');

INSERT INTO bf_dining_dish(store_id,category_id,dish_code,name,price_cent,image_url,description,recommended,special,status)
SELECT s.id,c.id,CONCAT(s.store_code,'_',v.dish_code),v.name,v.price_cent,NULL,v.description,v.recommended,0,'ACTIVE'
FROM (
    SELECT 'COLD' category_code,'MIXED_COLD' dish_code,'i某小程序什锦凉菜' name,3800 price_cent,'清爽开胃，时令搭配' description,1 recommended
    UNION ALL SELECT 'COLD','VINEGAR_PEANUT','老醋花生',2200,'酸甜爽脆，经典佐餐',0
    UNION ALL SELECT 'HOT','BRAISED_PORK','北方红烧肉',6800,'慢火焖制，软糯入味',1
    UNION ALL SELECT 'HOT','SWEET_SOUR_PORK','锅包肉',5800,'外酥里嫩，酸甜适口',1
    UNION ALL SELECT 'STAPLE','OAT_NOODLE','莜面窝窝',2800,'西北风味，粗粮主食',0
    UNION ALL SELECT 'DRINK','SEA_BUCKTHORN','沙棘汁',1600,'酸甜清爽，冷饮推荐',1
) v
CROSS JOIN bf_dining_store s
JOIN bf_dining_category c ON c.store_id=s.id AND c.category_code=v.category_code
WHERE s.store_code IN ('NORTH_HOTEL_DINING','HOUSHAN_DINING');

INSERT INTO bf_dining_stock(dish_id,on_hand_quantity,locked_quantity,warning_quantity)
SELECT id,100,0,15 FROM bf_dining_dish
WHERE dish_code LIKE 'NORTH_HOTEL_DINING_%' OR dish_code LIKE 'HOUSHAN_DINING_%';
