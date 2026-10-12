-- Fail closed for catalogs seeded only for development/integration. Keep rows and
-- inventory for order/audit references; operators may review and explicitly reactivate
-- records after company data, prices, contacts and operating rules are confirmed.

-- Hotel and the legacy hotel/meeting catalog attached to the same placeholder site.
UPDATE bf_hotel_price_rule rule_row
JOIN bf_hotel_room_type room_type ON room_type.id=rule_row.room_type_id
JOIN bf_hotel hotel ON hotel.id=room_type.hotel_id
SET rule_row.status='INACTIVE'
WHERE hotel.hotel_code='NORTH_HOTEL';

UPDATE bf_hotel_image image_row
LEFT JOIN bf_hotel hotel ON hotel.id=image_row.hotel_id
LEFT JOIN bf_hotel_room_type room_type ON room_type.id=image_row.room_type_id
LEFT JOIN bf_hotel room_hotel ON room_hotel.id=room_type.hotel_id
SET image_row.enabled=0
WHERE hotel.hotel_code='NORTH_HOTEL' OR room_hotel.hotel_code='NORTH_HOTEL';

UPDATE bf_hotel_room_type room_type
JOIN bf_hotel hotel ON hotel.id=room_type.hotel_id
SET room_type.status='INACTIVE'
WHERE hotel.hotel_code='NORTH_HOTEL';

UPDATE bf_hotel SET status='INACTIVE' WHERE hotel_code='NORTH_HOTEL';

-- Scenic and weapon-city products seeded with unconfirmed prices/contact details.
UPDATE bf_resource_sku sku
LEFT JOIN bf_resource resource_row ON resource_row.id=sku.resource_id
LEFT JOIN bf_business_site site ON site.id=resource_row.site_id
SET sku.status='INACTIVE'
WHERE site.site_code IN (
    'NORTH_HOTEL','SCENIC_WEAPON_CITY','SCENIC_WUDANGZHAO','SCENIC_MEIDAIZHAO',
    'SCENIC_SAIHANTALA','SCENIC_NANHAI'
) OR sku.sku_code IN ('HOTEL_STANDARD_001','MEETING_MEDIUM_AM')
   OR sku.sku_code LIKE 'HOTEL\_%'
   OR sku.sku_code LIKE 'SCENIC\_%';

UPDATE bf_resource resource_row
JOIN bf_business_site site ON site.id=resource_row.site_id
SET resource_row.status='INACTIVE'
WHERE site.site_code IN (
    'NORTH_HOTEL','SCENIC_WEAPON_CITY','SCENIC_WUDANGZHAO','SCENIC_MEIDAIZHAO',
    'SCENIC_SAIHANTALA','SCENIC_NANHAI'
);

UPDATE bf_business_site SET status='INACTIVE'
WHERE site_code IN (
    'NORTH_HOTEL','SCENIC_WEAPON_CITY','SCENIC_WUDANGZHAO','SCENIC_MEIDAIZHAO',
    'SCENIC_SAIHANTALA','SCENIC_NANHAI'
);

-- Cultural-store products from the bundled MALL_* sample catalog.
UPDATE bf_resource_sku SET status='INACTIVE' WHERE sku_code LIKE 'MALL\_%';
UPDATE bf_resource resource_row
JOIN bf_cultural_product_profile profile_row ON profile_row.resource_id=resource_row.id
SET resource_row.status='INACTIVE'
WHERE profile_row.product_code LIKE 'MALL\_%';

-- Dining stores, tables and products seeded with placeholder phone numbers and prices.
UPDATE bf_dining_package package_row
JOIN bf_dining_store store_row ON store_row.id=package_row.store_id
SET package_row.status='INACTIVE'
WHERE store_row.store_code IN ('NORTH_HOTEL_DINING','HOUSHAN_DINING');

UPDATE bf_dining_dish dish
JOIN bf_dining_store store_row ON store_row.id=dish.store_id
SET dish.status='INACTIVE'
WHERE store_row.store_code IN ('NORTH_HOTEL_DINING','HOUSHAN_DINING');

UPDATE bf_dining_category category_row
JOIN bf_dining_store store_row ON store_row.id=category_row.store_id
SET category_row.status='INACTIVE'
WHERE store_row.store_code IN ('NORTH_HOTEL_DINING','HOUSHAN_DINING');

UPDATE bf_dining_table table_row
JOIN bf_dining_store store_row ON store_row.id=table_row.store_id
SET table_row.status='INACTIVE'
WHERE store_row.store_code IN ('NORTH_HOTEL_DINING','HOUSHAN_DINING');

UPDATE bf_dining_store SET status='INACTIVE'
WHERE store_code IN ('NORTH_HOTEL_DINING','HOUSHAN_DINING');

-- Leisure venues and all availability/price rules created beneath their stable demo codes.
UPDATE bf_sport_price_rule price_rule
JOIN bf_sport_venue venue ON venue.id=price_rule.venue_id
SET price_rule.status='INACTIVE'
WHERE venue.venue_code IN ('IMINIAPP_BATH','IMINIAPP_SPORTS');

UPDATE bf_sport_opening_rule opening_rule
JOIN bf_sport_court court ON court.id=opening_rule.court_id
JOIN bf_sport_venue venue ON venue.id=court.venue_id
SET opening_rule.status='INACTIVE'
WHERE venue.venue_code IN ('IMINIAPP_BATH','IMINIAPP_SPORTS');

UPDATE bf_sport_court court
JOIN bf_sport_venue venue ON venue.id=court.venue_id
SET court.status='INACTIVE'
WHERE venue.venue_code IN ('IMINIAPP_BATH','IMINIAPP_SPORTS');

UPDATE bf_sport_court_type court_type
JOIN bf_sport_venue venue ON venue.id=court_type.venue_id
SET court_type.status='INACTIVE'
WHERE venue.venue_code IN ('IMINIAPP_BATH','IMINIAPP_SPORTS');

UPDATE bf_sport_venue SET status='INACTIVE'
WHERE venue_code IN ('IMINIAPP_BATH','IMINIAPP_SPORTS');
