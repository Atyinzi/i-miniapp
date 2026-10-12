-- 文创商城运营报表查询索引。
CREATE INDEX idx_payment_status_paid_order ON bf_payment(status, paid_at, order_id);
CREATE INDEX idx_cultural_after_sale_completed ON bf_cultural_after_sale(status, completed_at);
CREATE INDEX idx_cultural_stock_log_type_time ON bf_cultural_stock_log(change_type, created_at);
