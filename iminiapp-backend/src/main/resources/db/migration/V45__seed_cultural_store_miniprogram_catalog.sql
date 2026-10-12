-- 小程序特色商城联调目录：分类、六个商品、十二个 SKU 和实体库存。

INSERT INTO bf_cultural_category(category_code,name,sort_order,status) VALUES
('FEATURED','特色产品',10,'ACTIVE'),
('HANDCRAFT','手工艺品',20,'ACTIVE'),
('SNACK','特殊零食',30,'ACTIVE');

INSERT INTO bf_resource(resource_type,category_code,name,description,cover_url,status,attributes) VALUES
('CULTURAL_PRODUCT','FEATURED','i某小程序工业铭牌冰箱贴','以i某小程序工业铭牌为灵感，留住一段工业记忆','/assets/images/mall/industrial-magnet.jpg','ACTIVE',JSON_OBJECT('edition','INDUSTRIAL MEMORY','tone','brass','art','铭')),
('CULTURAL_PRODUCT','FEATURED','兵器城坦克合金模型','合金车身与精细涂装，适合收藏和桌面陈列','/assets/images/mall/tank-model.jpg','ACTIVE',JSON_OBJECT('edition','ARMORED COLLECTION','tone','army','art','坦')),
('CULTURAL_PRODUCT','HANDCRAFT','i某小程序文创帆布袋','厚实帆布结合工业线稿，轻装出发装下热爱','/assets/images/mall/canvas-bag.jpg','ACTIVE',JSON_OBJECT('edition','CARRY THE STORY','tone','brick','art','袋')),
('CULTURAL_PRODUCT','HANDCRAFT','工业风手作保温杯','机械线条与磨砂杯身，把温暖带在身边','/assets/images/mall/thermal-cup.jpg','ACTIVE',JSON_OBJECT('edition','STEEL & WARMTH','tone','steel','art','杯')),
('CULTURAL_PRODUCT','SNACK','i某小程序沙棘果糕','酸甜果香与独立小包装，旅途中轻松分享','/assets/images/mall/sea-buckthorn-cake.jpg','ACTIVE',JSON_OBJECT('edition','LOCAL FLAVOR','tone','orange','art','果')),
('CULTURAL_PRODUCT','SNACK','i某小程序茶点伴手礼盒','地方茶点与定制包装，适合作为旅行伴手礼','/assets/images/mall/tea-gift.jpg','ACTIVE',JSON_OBJECT('edition','IMINIAPP GIFT','tone','olive','art','礼'));

INSERT INTO bf_cultural_product_profile(resource_id,category_id,product_code,detail_html,market_price_cent,weight_gram,sales_count)
SELECT r.id,c.id,v.product_code,v.detail_html,v.market_price_cent,v.weight_gram,v.sales_count
FROM (
    SELECT 'i某小程序工业铭牌冰箱贴' name,'MALL_MAGNET' product_code,'金属工艺文创冰箱贴' detail_html,3990 market_price_cent,80 weight_gram,326 sales_count
    UNION ALL SELECT '兵器城坦克合金模型','MALL_TANK_MODEL','合金车身、精细涂装与收藏礼盒',15900,650,218
    UNION ALL SELECT 'i某小程序文创帆布袋','MALL_CANVAS_BAG','厚实帆布与工业线稿印花',8900,260,196
    UNION ALL SELECT '工业风手作保温杯','MALL_THERMAL_CUP','磨砂杯身，500ml 容量',15900,520,154
    UNION ALL SELECT 'i某小程序沙棘果糕','MALL_SEA_BUCKTHORN','独立小包装，净含量 180g',4680,220,428
    UNION ALL SELECT 'i某小程序茶点伴手礼盒','MALL_TEA_GIFT','地方茶点定制伴手礼盒',19800,1200,137
) v
JOIN bf_resource r ON r.resource_type='CULTURAL_PRODUCT' AND r.name=v.name
JOIN bf_cultural_category c ON c.category_code=r.category_code;

INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
SELECT r.id,v.sku_code,v.sku_name,v.price_cent,'ACTIVE',JSON_OBJECT('specification',v.sku_name)
FROM (
    SELECT 'i某小程序工业铭牌冰箱贴' product_name,'MALL_MAGNET_BRASS' sku_code,'黄铜色' sku_name,2990 price_cent
    UNION ALL SELECT 'i某小程序工业铭牌冰箱贴','MALL_MAGNET_STEEL','钢灰色',2990
    UNION ALL SELECT '兵器城坦克合金模型','MALL_TANK_ARMY','军绿色',12900
    UNION ALL SELECT '兵器城坦克合金模型','MALL_TANK_DESERT','沙漠色',12900
    UNION ALL SELECT 'i某小程序文创帆布袋','MALL_BAG_IVORY','米白色',6900
    UNION ALL SELECT 'i某小程序文创帆布袋','MALL_BAG_BRICK','砖红色',6900
    UNION ALL SELECT '工业风手作保温杯','MALL_CUP_GREY','深空灰 500ml',12900
    UNION ALL SELECT '工业风手作保温杯','MALL_CUP_GREEN','墨绿色 500ml',12900
    UNION ALL SELECT 'i某小程序沙棘果糕','MALL_CAKE_ORIGINAL','原味 180g',3680
    UNION ALL SELECT 'i某小程序沙棘果糕','MALL_CAKE_MIXED','混合口味 180g',3680
    UNION ALL SELECT 'i某小程序茶点伴手礼盒','MALL_TEA_CLASSIC','经典礼盒',15800
    UNION ALL SELECT 'i某小程序茶点伴手礼盒','MALL_TEA_PREMIUM','臻选礼盒',18800
) v
JOIN bf_resource r ON r.resource_type='CULTURAL_PRODUCT' AND r.name=v.product_name;

INSERT INTO bf_cultural_stock(sku_id,on_hand_quantity,locked_quantity,available_quantity,warning_threshold)
SELECT sku.id,v.quantity,0,v.quantity,v.warning_threshold
FROM (
    SELECT 'MALL_MAGNET_BRASS' sku_code,44 quantity,10 warning_threshold
    UNION ALL SELECT 'MALL_MAGNET_STEEL',44,10
    UNION ALL SELECT 'MALL_TANK_ARMY',18,5
    UNION ALL SELECT 'MALL_TANK_DESERT',18,5
    UNION ALL SELECT 'MALL_BAG_IVORY',29,8
    UNION ALL SELECT 'MALL_BAG_BRICK',28,8
    UNION ALL SELECT 'MALL_CUP_GREY',21,6
    UNION ALL SELECT 'MALL_CUP_GREEN',21,6
    UNION ALL SELECT 'MALL_CAKE_ORIGINAL',60,15
    UNION ALL SELECT 'MALL_CAKE_MIXED',60,15
    UNION ALL SELECT 'MALL_TEA_CLASSIC',16,5
    UNION ALL SELECT 'MALL_TEA_PREMIUM',15,5
) v JOIN bf_resource_sku sku ON sku.sku_code=v.sku_code;

INSERT INTO bf_resource_image(resource_id,image_url,image_type,sort_order)
SELECT id,cover_url,'COVER',0 FROM bf_resource
WHERE resource_type='CULTURAL_PRODUCT' AND category_code IN ('FEATURED','HANDCRAFT','SNACK');
