-- 体育场馆阶段4：会员等级、权益、储值账户与不可变资金流水。
CREATE TABLE bf_sport_member_level (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level_code VARCHAR(32) NOT NULL,
    level_name VARCHAR(64) NOT NULL,
    sport_discount_bp INT NOT NULL DEFAULT 10000,
    min_growth_value INT NOT NULL DEFAULT 0,
    benefits JSON,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_member_level_code (level_code),
    CONSTRAINT chk_sport_member_discount CHECK (sport_discount_bp BETWEEN 1 AND 10000),
    CONSTRAINT chk_sport_member_growth CHECK (min_growth_value >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育会员等级权益';

INSERT INTO bf_sport_member_level(level_code,level_name,sport_discount_bp,min_growth_value,benefits,status) VALUES
('NORMAL','普通会员',10000,0,JSON_ARRAY('会员储值','消费记录查询'),'ACTIVE'),
('SILVER','银卡会员',9500,1000,JSON_ARRAY('场地预订95折','优先活动通知'),'ACTIVE'),
('GOLD','金卡会员',9000,5000,JSON_ARRAY('场地预订9折','优先活动通知','会员专属时段'),'ACTIVE');

ALTER TABLE bf_member_account
    ADD COLUMN member_no VARCHAR(32) NULL AFTER id,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER level_code,
    ADD COLUMN registered_at DATETIME NULL AFTER version,
    ADD UNIQUE KEY uk_member_no (member_no),
    ADD INDEX idx_member_level_status (level_code,status);

UPDATE bf_member_account
SET member_no=CONCAT('SM',LPAD(id,12,'0')),registered_at=COALESCE(registered_at,created_at)
WHERE member_no IS NULL;

ALTER TABLE bf_member_account MODIFY member_no VARCHAR(32) NOT NULL;

CREATE TABLE bf_sport_wallet_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_account_id BIGINT NOT NULL,
    transaction_no VARCHAR(40) NOT NULL,
    transaction_type VARCHAR(24) NOT NULL,
    amount_cent BIGINT NOT NULL,
    balance_before_cent BIGINT NOT NULL,
    balance_after_cent BIGINT NOT NULL,
    order_id BIGINT,
    reference_no VARCHAR(64),
    idempotency_key VARCHAR(96) NOT NULL,
    remark VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sport_wallet_transaction_no (transaction_no),
    UNIQUE KEY uk_sport_wallet_idempotency (member_account_id,idempotency_key),
    INDEX idx_sport_wallet_member_time (member_account_id,created_at),
    INDEX idx_sport_wallet_order (order_id),
    CONSTRAINT fk_sport_wallet_member FOREIGN KEY (member_account_id) REFERENCES bf_member_account(id),
    CONSTRAINT fk_sport_wallet_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT chk_sport_wallet_amount CHECK (amount_cent <> 0),
    CONSTRAINT chk_sport_wallet_balance CHECK (balance_before_cent >= 0 AND balance_after_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体育会员储值不可变流水';

ALTER TABLE bf_sport_booking
    ADD COLUMN original_price_cent BIGINT NULL AFTER price_cent,
    ADD COLUMN member_discount_cent BIGINT NOT NULL DEFAULT 0 AFTER original_price_cent,
    ADD COLUMN member_account_id BIGINT NULL AFTER member_discount_cent,
    ADD COLUMN member_level_code VARCHAR(32) NULL AFTER member_account_id,
    ADD CONSTRAINT fk_sport_booking_member FOREIGN KEY (member_account_id) REFERENCES bf_member_account(id),
    ADD CONSTRAINT chk_sport_booking_member_discount CHECK (member_discount_cent >= 0);

UPDATE bf_sport_booking SET original_price_cent=price_cent WHERE original_price_cent IS NULL;
ALTER TABLE bf_sport_booking MODIFY original_price_cent BIGINT NOT NULL;
