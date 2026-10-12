-- V18 historically inserted demonstration fleet records into every environment.
-- Deactivate first so records with fulfillment history remain auditable but can never be dispatched again.
UPDATE bf_vehicle
SET status = 'INACTIVE'
WHERE vehicle_no IN ('BFH-CAR-001', 'BFH-CAR-002', 'BFH-CAR-003');

UPDATE bf_driver
SET status = 'INACTIVE',
    -- V18 stored these values as raw UTF-8 in columns that otherwise contain AES-GCM ciphertext.
    -- A one-byte tombstone irreversibly removes the embedded demo PII and is handled fail-safe by the application.
    name_cipher = UNHEX('00'),
    mobile_cipher = UNHEX('00'),
    license_no_cipher = UNHEX('00'),
    license_no_hash = SHA2(CONCAT('RETIRED-DEMO-DRIVER:', driver_no), 256)
WHERE driver_no IN ('BFH-DRIVER-001', 'BFH-DRIVER-002');

UPDATE bf_user
SET status = 'INACTIVE', nickname = '已停用演示司机'
WHERE wechat_openid IN ('driver:BFH-001', 'driver:BFH-002');

-- Unreferenced availability rows carry no fulfillment history and may be safely removed.
DELETE period
FROM bf_vehicle_unavailable_period period
JOIN bf_vehicle vehicle ON vehicle.id = period.vehicle_id
WHERE vehicle.vehicle_no IN ('BFH-CAR-001', 'BFH-CAR-002', 'BFH-CAR-003')
  AND NOT EXISTS (
      SELECT 1 FROM bf_vehicle_dispatch dispatch_record
      WHERE dispatch_record.vehicle_id = vehicle.id
  );

-- Preserve vehicles/drivers referenced by dispatches; delete only unreferenced demo inventory.
DELETE vehicle
FROM bf_vehicle vehicle
WHERE vehicle.vehicle_no IN ('BFH-CAR-001', 'BFH-CAR-002', 'BFH-CAR-003')
  AND NOT EXISTS (
      SELECT 1 FROM bf_vehicle_dispatch dispatch_record
      WHERE dispatch_record.vehicle_id = vehicle.id
  );

DELETE driver
FROM bf_driver driver
WHERE driver.driver_no IN ('BFH-DRIVER-001', 'BFH-DRIVER-002')
  AND NOT EXISTS (
      SELECT 1 FROM bf_vehicle_dispatch dispatch_record
      WHERE dispatch_record.driver_id = driver.id
  );
