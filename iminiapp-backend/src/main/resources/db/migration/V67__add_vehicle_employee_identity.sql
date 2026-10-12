-- 内部用车员工身份、组织关系及能力角色。
CREATE TABLE bf_department (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    department_code VARCHAR(50) NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    parent_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_department_code (department_code),
    INDEX idx_department_parent_status (parent_id, status),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES bf_department(id),
    CONSTRAINT chk_department_status CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部员工组织部门';

CREATE TABLE bf_employee (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    department_id BIGINT NOT NULL,
    employee_no VARCHAR(50) NOT NULL,
    name_cipher VARBINARY(512) NOT NULL,
    mobile_cipher VARBINARY(512) NOT NULL,
    mobile_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    bound_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_employee_no (employee_no),
    UNIQUE KEY uk_employee_mobile_hash (mobile_hash),
    UNIQUE KEY uk_employee_user (user_id),
    INDEX idx_employee_department_status (department_id, status),
    CONSTRAINT fk_employee_user FOREIGN KEY (user_id) REFERENCES bf_user(id),
    CONSTRAINT fk_employee_department FOREIGN KEY (department_id) REFERENCES bf_department(id),
    CONSTRAINT chk_employee_status CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部用车员工身份档案';

INSERT IGNORE INTO bf_department(department_code,department_name,status)
VALUES ('DEFAULT','待分配部门','ACTIVE');
UPDATE bf_department SET department_name='待分配部门' WHERE department_code='DEFAULT';

INSERT IGNORE INTO bf_role(code,name) VALUES
('EMPLOYEE','内部员工'),('DRIVER','车辆司机');
UPDATE bf_role SET name=CASE code WHEN 'EMPLOYEE' THEN '内部员工' ELSE '车辆司机' END
WHERE code IN ('EMPLOYEE','DRIVER');

INSERT IGNORE INTO bf_permission(code,name,permission_type) VALUES
('vehicle.internal.apply','提交内部用车申请','API'),
('vehicle.driver.fulfill','处理本人派车任务','API');
UPDATE bf_permission SET
name=CASE code WHEN 'vehicle.internal.apply' THEN '提交内部用车申请' ELSE '处理本人派车任务' END,
permission_type='API'
WHERE code IN ('vehicle.internal.apply','vehicle.driver.fulfill');

INSERT IGNORE INTO bf_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bf_role r CROSS JOIN bf_permission p
WHERE r.code='EMPLOYEE' AND p.code='vehicle.internal.apply';

INSERT IGNORE INTO bf_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM bf_role r CROSS JOIN bf_permission p
WHERE r.code='DRIVER' AND p.code='vehicle.driver.fulfill';

INSERT IGNORE INTO bf_user_role(user_id,role_id)
SELECT d.user_id,r.id FROM bf_driver d JOIN bf_role r ON r.code='DRIVER'
WHERE d.user_id IS NOT NULL;
