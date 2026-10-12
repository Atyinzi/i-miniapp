-- 通知基础设施。通知表不复制手机号、OpenID，也不保存供应商密钥。
CREATE TABLE bf_notification_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_notification_preference (user_id, event_type, channel),
    INDEX idx_notification_preference_user (user_id, channel),
    CONSTRAINT fk_notification_preference_user FOREIGN KEY (user_id) REFERENCES bf_user(id),
    CONSTRAINT chk_notification_preference_channel CHECK (channel IN ('WECHAT_SUBSCRIPTION', 'SMS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户通知渠道偏好';

CREATE TABLE bf_notification_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_ref VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    template_code VARCHAR(64),
    deduplication_key CHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(255),
    sent_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_notification_deduplication (deduplication_key),
    INDEX idx_notification_due (status, next_attempt_at, id),
    INDEX idx_notification_user_time (user_id, created_at),
    INDEX idx_notification_business (business_type, business_ref, event_type),
    CONSTRAINT fk_notification_task_user FOREIGN KEY (user_id) REFERENCES bf_user(id),
    CONSTRAINT chk_notification_task_channel CHECK (channel IN ('WECHAT_SUBSCRIPTION', 'SMS')),
    CONSTRAINT chk_notification_task_status CHECK (status IN (
        'PENDING', 'PROCESSING', 'RETRY', 'WAITING_CONFIGURATION',
        'SENT', 'FAILED', 'SKIPPED_PREFERENCE'
    )),
    CONSTRAINT chk_notification_attempts CHECK (
        attempt_count >= 0 AND max_attempts BETWEEN 1 AND 20 AND attempt_count <= max_attempts
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可靠通知任务';

CREATE TABLE bf_notification_delivery (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    external_message_id VARCHAR(128),
    error_code VARCHAR(64),
    error_message VARCHAR(255),
    duration_ms BIGINT NOT NULL DEFAULT 0,
    attempted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_notification_delivery_attempt (task_id, attempt_no),
    INDEX idx_notification_delivery_rate (delivery_status, attempted_at, task_id),
    CONSTRAINT fk_notification_delivery_task FOREIGN KEY (task_id) REFERENCES bf_notification_task(id),
    CONSTRAINT chk_notification_delivery_status CHECK (delivery_status IN (
        'SENT', 'RETRYABLE_FAILURE', 'FINAL_FAILURE', 'NOT_CONFIGURED',
        'RATE_LIMITED', 'MISSING_RECIPIENT'
    )),
    CONSTRAINT chk_notification_delivery_duration CHECK (duration_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知投递审计记录';
