-- Preserve historical pickup rows for audit, while making the default path reject
-- every newly inserted pickup reservation. Application APIs never expose or set
-- legacy_pickup_record, so only rows present at migration time are grandfathered.
ALTER TABLE bf_dining_reservation
    ADD COLUMN legacy_pickup_record TINYINT(1) NOT NULL DEFAULT 0 AFTER fulfillment_type;

UPDATE bf_dining_reservation
SET legacy_pickup_record = 1
WHERE fulfillment_type = 'PICKUP';

ALTER TABLE bf_dining_reservation
    ADD CONSTRAINT chk_dining_pickup_legacy_only CHECK (
        fulfillment_type <> 'PICKUP' OR legacy_pickup_record = 1
    ),
    ADD CONSTRAINT chk_dining_pickup_legacy_flag CHECK (
        legacy_pickup_record = 0 OR fulfillment_type = 'PICKUP'
    );

UPDATE bf_resource
SET description = '堂食及宾馆内部配送统一订单',
    attributes = JSON_SET(
        COALESCE(attributes, JSON_OBJECT()),
        '$.pickupEnabled', FALSE,
        '$.deliveryScope', 'HOTEL_INTERNAL_ONLY'
    )
WHERE resource_type = 'DINING_ORDER';
