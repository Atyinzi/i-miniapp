-- 会议预订阶段 5：可版本化多级审批规则及审批任务快照。
CREATE TABLE bf_meeting_approval_config (
    id TINYINT PRIMARY KEY,
    current_version INT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_meeting_approval_config_user FOREIGN KEY (updated_by) REFERENCES bf_user(id),
    CONSTRAINT chk_meeting_approval_config_singleton CHECK (id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议审批流程当前版本';

INSERT INTO bf_meeting_approval_config(id,current_version) VALUES (1,0);

CREATE TABLE bf_meeting_approval_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_version INT NOT NULL,
    step_no INT NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    approver_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_meeting_approval_rule_step (workflow_version,step_no),
    INDEX idx_meeting_approval_rule_active (status,workflow_version,step_no),
    CONSTRAINT fk_meeting_approval_rule_approver FOREIGN KEY (approver_id) REFERENCES bf_user(id),
    CONSTRAINT fk_meeting_approval_rule_creator FOREIGN KEY (created_by) REFERENCES bf_user(id),
    CONSTRAINT chk_meeting_approval_rule_step CHECK (step_no BETWEEN 1 AND 20),
    CONSTRAINT chk_meeting_approval_rule_status CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议多级审批规则';

ALTER TABLE bf_approval_instance
    ADD COLUMN workflow_version INT NULL AFTER current_step,
    ADD INDEX idx_approval_business_status (business_type,status,created_at);

ALTER TABLE bf_approval_task
    ADD COLUMN step_name VARCHAR(100) NULL AFTER step_no,
    ADD INDEX idx_approval_instance_step (instance_id,step_no,status);
