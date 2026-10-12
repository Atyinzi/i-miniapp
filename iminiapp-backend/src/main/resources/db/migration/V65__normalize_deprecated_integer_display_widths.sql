-- MySQL 8 deprecated integer display widths. Historical migrations are immutable,
-- so normalize the resulting schema with a forward-only migration.
ALTER TABLE bf_dining_dish
    MODIFY COLUMN recommended TINYINT NOT NULL DEFAULT 0,
    MODIFY COLUMN special TINYINT NOT NULL DEFAULT 0;

ALTER TABLE bf_dining_package
    MODIFY COLUMN recommended TINYINT NOT NULL DEFAULT 0;

ALTER TABLE bf_notification_preference
    MODIFY COLUMN enabled TINYINT NOT NULL DEFAULT 1;

ALTER TABLE bf_dining_reservation
    MODIFY COLUMN legacy_pickup_record TINYINT NOT NULL DEFAULT 0;
