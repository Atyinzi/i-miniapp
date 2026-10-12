-- 文创商城发货物流闭环。

CREATE TABLE bf_logistics_carrier (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    carrier_code VARCHAR(32) NOT NULL,
    carrier_name VARCHAR(64) NOT NULL,
    tracking_url_template VARCHAR(512),
    enabled TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_logistics_carrier_code (carrier_code),
    INDEX idx_logistics_carrier_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流公司配置';

ALTER TABLE bf_shipment
    ADD COLUMN prepared_at DATETIME NULL AFTER status,
    ADD COLUMN shipped_by BIGINT NULL AFTER shipped_at,
    ADD COLUMN auto_receive_at DATETIME NULL AFTER shipped_by,
    ADD COLUMN last_tracking_at DATETIME NULL AFTER received_at,
    ADD COLUMN exception_flag TINYINT NOT NULL DEFAULT 0 AFTER last_tracking_at,
    ADD COLUMN exception_reason VARCHAR(255) NULL AFTER exception_flag,
    ADD UNIQUE KEY uk_shipment_carrier_tracking (carrier_code, tracking_no),
    ADD INDEX idx_shipment_status_auto_receive (status, auto_receive_at),
    ADD CONSTRAINT fk_shipment_operator FOREIGN KEY (shipped_by) REFERENCES bf_user(id);

CREATE TABLE bf_shipment_tracking (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    event_code VARCHAR(32) NOT NULL,
    event_description VARCHAR(500) NOT NULL,
    location VARCHAR(255),
    occurred_at DATETIME NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    external_event_id VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shipment_external_event (shipment_id, external_event_id),
    INDEX idx_shipment_tracking_time (shipment_id, occurred_at, id),
    CONSTRAINT fk_shipment_tracking_shipment FOREIGN KEY (shipment_id) REFERENCES bf_shipment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹';

CREATE TABLE bf_shipment_operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_id BIGINT,
    order_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    operator_id BIGINT,
    idempotency_key VARCHAR(100) NOT NULL,
    detail JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shipment_operation_idempotency (idempotency_key),
    INDEX idx_shipment_operation_order_time (order_id, created_at),
    CONSTRAINT fk_shipment_operation_shipment FOREIGN KEY (shipment_id) REFERENCES bf_shipment(id),
    CONSTRAINT fk_shipment_operation_order FOREIGN KEY (order_id) REFERENCES bf_order(id),
    CONSTRAINT fk_shipment_operation_operator FOREIGN KEY (operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货物流操作流水';

INSERT INTO bf_logistics_carrier(carrier_code,carrier_name,tracking_url_template,sort_order) VALUES
('SF','顺丰速运','https://www.sf-express.com/chn/sc/dynamic_function/waybill/#search/bill-number/{trackingNo}',10),
('JD','京东物流','https://www.jdl.com/orderSearch/?waybillCodes={trackingNo}',20),
('YTO','圆通速递','https://www.yto.net.cn/waybillQuery.html?waybillNo={trackingNo}',30),
('ZTO','中通快递','https://www.zto.com/express/expressCheck.html?txtbill={trackingNo}',40),
('STO','申通快递','https://www.sto.cn/pc/track?billcode={trackingNo}',50),
('YUNDA','韵达速递','https://www.yundaex.com/cn/index.php?m=public&a=search&num={trackingNo}',60),
('EMS','中国邮政EMS','https://www.ems.com.cn/queryList?mailNum={trackingNo}',70);
