-- 将车辆补款支付与唯一结算单建立强关联，避免同一结算重复创建渠道支付单。
ALTER TABLE bf_payment
    ADD COLUMN vehicle_settlement_id BIGINT NULL AFTER order_id,
    ADD UNIQUE KEY uk_payment_vehicle_settlement (vehicle_settlement_id),
    ADD CONSTRAINT fk_payment_vehicle_settlement
        FOREIGN KEY (vehicle_settlement_id) REFERENCES bf_vehicle_settlement(id);
