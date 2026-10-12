-- 管理后台独立账号。密码只保存 BCrypt 哈希，业务用户资料和角色继续复用 bf_user / bf_user_role。
CREATE TABLE bf_admin_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until DATETIME NULL,
    last_login_at DATETIME NULL,
    last_login_ip VARCHAR(64) NULL,
    password_changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_admin_account_username (username),
    UNIQUE KEY uk_admin_account_user (user_id),
    CONSTRAINT fk_admin_account_user FOREIGN KEY (user_id) REFERENCES bf_user(id),
    CONSTRAINT chk_admin_account_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT chk_admin_failed_attempts CHECK (failed_login_attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理后台登录账号';
