-- 订单状态与通知任务解耦。触发器和业务状态更新处于同一事务，避免服务重启造成通知事件丢失。
CREATE TABLE bf_order_notification_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(255),
    processed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_notification_pending (processed_at, id),
    INDEX idx_order_notification_order (order_id, id),
    CONSTRAINT fk_order_notification_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_order_notification_user FOREIGN KEY (user_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单通知事务型出站事件';

CREATE TRIGGER trg_order_notification_created
AFTER INSERT ON bf_order
FOR EACH ROW
INSERT INTO bf_order_notification_outbox
    (order_id,user_id,order_no,event_type,from_status,to_status)
VALUES
    (NEW.id,NEW.user_id,NEW.order_no,'ORDER_CREATED',NULL,NEW.status);

CREATE TRIGGER trg_order_notification_status_changed
AFTER UPDATE ON bf_order
FOR EACH ROW
INSERT INTO bf_order_notification_outbox
    (order_id,user_id,order_no,event_type,from_status,to_status)
SELECT NEW.id,NEW.user_id,NEW.order_no,CONCAT('ORDER_',NEW.status),OLD.status,NEW.status
FROM DUAL
WHERE NOT (OLD.status <=> NEW.status);
