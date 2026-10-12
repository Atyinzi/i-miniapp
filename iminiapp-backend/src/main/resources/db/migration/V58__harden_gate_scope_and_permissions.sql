-- 闸机权限与业务场馆绑定。管理员不再依赖宽泛的 catalog.manage。
INSERT INTO bf_permission(code,name,permission_type) VALUES
('gate.manage','管理闸机设备与密钥','MENU'),
('notifications.view','查看通知任务与投递记录','MENU'),
('notifications.manage','重排和管理通知任务','API')
ON DUPLICATE KEY UPDATE name=VALUES(name),permission_type=VALUES(permission_type);

INSERT IGNORE INTO bf_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bf_role r CROSS JOIN bf_permission p
WHERE r.code='ADMIN' AND p.code IN ('gate.manage','notifications.view','notifications.manage');

-- 已创建但未明确绑定场馆的闸机继续保持 INACTIVE，避免配置迁移后误启用。
UPDATE bf_gate_device SET status='INACTIVE' WHERE venue_code IS NULL OR TRIM(venue_code)='';
