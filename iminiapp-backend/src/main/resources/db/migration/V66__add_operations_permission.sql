-- Separate day-to-day fulfillment actions from broad catalog maintenance.
INSERT IGNORE INTO bf_permission(code,name,permission_type)
VALUES ('operations.manage','处理履约与运营任务','API');

UPDATE bf_permission
SET name='处理履约与运营任务', permission_type='API'
WHERE code='operations.manage';

INSERT IGNORE INTO bf_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bf_role r CROSS JOIN bf_permission p
WHERE r.code IN ('ADMIN','OPERATIONS') AND p.code='operations.manage';
