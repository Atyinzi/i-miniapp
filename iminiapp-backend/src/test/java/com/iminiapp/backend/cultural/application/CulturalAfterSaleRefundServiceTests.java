package com.iminiapp.backend.cultural.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CulturalAfterSaleRefundServiceTests {

    @Test
    void ignoresRefundsThatDoNotBelongToCulturalAfterSales() {
        CulturalJdbc jdbc = new CulturalJdbc(false);
        CulturalAfterSaleRefundService service = new CulturalAfterSaleRefundService(jdbc);

        assertFalse(service.handleWechatCallback(501L, "SUCCESS", null));
        assertEquals("PROCESSING", jdbc.refundStatus);
        assertEquals(0, jdbc.stockRestoreCount);
    }

    @Test
    void successfulWechatCallbackCompletesAllStatesAndIsIdempotent() {
        CulturalJdbc jdbc = new CulturalJdbc(true);
        CulturalAfterSaleRefundService service = new CulturalAfterSaleRefundService(jdbc);

        assertTrue(service.handleWechatCallback(501L, "SUCCESS", null));
        assertTrue(service.handleWechatCallback(501L, "SUCCESS", null));

        assertEquals("SUCCESS", jdbc.refundStatus);
        assertEquals("REFUNDED", jdbc.orderStatus);
        assertEquals("COMPLETED", jdbc.afterSaleStatus);
        assertEquals(1, jdbc.stockRestoreCount);
        assertEquals(1, jdbc.successAuditCount);
    }

    @Test
    void closedWechatRefundRestoresBusinessStateForControlledRetry() {
        CulturalJdbc jdbc = new CulturalJdbc(true);
        CulturalAfterSaleRefundService service = new CulturalAfterSaleRefundService(jdbc);

        assertTrue(service.handleWechatCallback(501L, "CLOSED", "channel closed"));
        assertTrue(service.handleWechatCallback(501L, "CLOSED", "duplicate callback"));

        assertEquals("FAILED", jdbc.refundStatus);
        assertEquals("PAID", jdbc.orderStatus);
        assertEquals("REFUND_FAILED", jdbc.afterSaleStatus);
        assertEquals("channel closed", jdbc.failureReason);
        assertEquals(0, jdbc.stockRestoreCount);
        assertEquals(1, jdbc.closedAuditCount);
    }

    @Test
    void abnormalWechatRefundStaysProcessingUntilAConclusiveCallback() {
        CulturalJdbc jdbc = new CulturalJdbc(true);
        CulturalAfterSaleRefundService service = new CulturalAfterSaleRefundService(jdbc);

        assertTrue(service.handleWechatCallback(501L, "ABNORMAL", "manual review"));
        assertTrue(service.handleWechatCallback(501L, "ABNORMAL", "duplicate callback"));

        assertEquals("PROCESSING", jdbc.refundStatus);
        assertEquals("REFUNDING", jdbc.orderStatus);
        assertEquals("REFUNDING", jdbc.afterSaleStatus);
        assertEquals("duplicate callback", jdbc.failureReason);
        assertEquals(0, jdbc.stockRestoreCount);
        assertEquals(1, jdbc.abnormalAuditCount);
    }

    private static ResultSet row(Map<String, Object> values) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.getArgument(0, String.class));
            return value == null ? null : String.valueOf(value);
        });
        when(rs.getLong(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.getArgument(0, String.class));
            return value == null ? 0L : ((Number) value).longValue();
        });
        when(rs.getInt(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.getArgument(0, String.class));
            return value == null ? 0 : ((Number) value).intValue();
        });
        when(rs.getObject(anyString(), org.mockito.ArgumentMatchers.<Class<Object>>any()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0, String.class)));
        return rs;
    }

    private static <T> List<T> map(RowMapper<T> mapper, Map<String, Object> values) {
        try {
            return List.of(mapper.mapRow(row(values), 0));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static final class CulturalJdbc extends JdbcTemplate {
        private final boolean cultural;
        private String refundStatus = "PROCESSING";
        private String orderStatus = "REFUNDING";
        private String afterSaleStatus = "REFUNDING";
        private String failureReason;
        private boolean stockLogExists;
        private boolean abnormalLogged;
        private int stockRestoreCount;
        private int successAuditCount;
        private int closedAuditCount;
        private int abnormalAuditCount;

        private CulturalJdbc(boolean cultural) {
            this.cultural = cultural;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            String query = normalized(sql);
            if (query.contains("FROM bf_cultural_after_sale a")) {
                if (!cultural) return List.of();
                return map(mapper, Map.ofEntries(
                        Map.entry("id", 101L),
                        Map.entry("order_id", 20L),
                        Map.entry("order_no", "CUL-20"),
                        Map.entry("order_status", orderStatus),
                        Map.entry("after_sale_type", "REFUND_ONLY"),
                        Map.entry("after_sale_status", afterSaleStatus),
                        Map.entry("refund_amount_cent", 8000L),
                        Map.entry("requested_by", 3L),
                        Map.entry("reviewed_by", 4L),
                        Map.entry("refund_status", refundStatus),
                        Map.entry("previous_order_status", "PAID")));
            }
            if (query.contains("FROM bf_order_item") && query.contains("resource_type='CULTURAL_PRODUCT'")) {
                return map(mapper, Map.of("id", 301L, "sku_id", 401L, "quantity", 2));
            }
            throw new AssertionError("Unexpected cultural refund query: " + query);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (normalized(sql).contains("FROM bf_cultural_stock_log")) {
                return (T) Integer.valueOf(stockLogExists ? 1 : 0);
            }
            throw new AssertionError("Unexpected cultural refund scalar query: " + normalized(sql));
        }

        @Override
        public int update(String sql, Object... args) {
            String update = normalized(sql);
            if (update.startsWith("UPDATE bf_cultural_stock SET")) {
                stockRestoreCount++;
                return 1;
            }
            if (update.startsWith("INSERT INTO bf_cultural_stock_log")) {
                stockLogExists = true;
                return 1;
            }
            if (update.contains("UPDATE bf_refund") && update.contains("status='SUCCESS'")) {
                if (!"PROCESSING".equals(refundStatus)) return 0;
                refundStatus = "SUCCESS";
                failureReason = null;
                return 1;
            }
            if (update.contains("UPDATE bf_order SET status='REFUNDED'")) {
                if (!"REFUNDING".equals(orderStatus)) return 0;
                orderStatus = "REFUNDED";
                return 1;
            }
            if (update.contains("UPDATE bf_cultural_after_sale") && update.contains("status='COMPLETED'")) {
                if (!"REFUNDING".equals(afterSaleStatus)) return 0;
                afterSaleStatus = "COMPLETED";
                return 1;
            }
            if (update.startsWith("UPDATE bf_shipment SET status='CANCELLED'")) return 1;
            if (update.contains("UPDATE bf_refund SET status='FAILED'")) {
                if (!"PROCESSING".equals(refundStatus)) return 0;
                refundStatus = "FAILED";
                failureReason = (String) args[0];
                return 1;
            }
            if (update.startsWith("UPDATE bf_order SET status=?")) {
                if (!"REFUNDING".equals(orderStatus)) return 0;
                orderStatus = (String) args[0];
                return 1;
            }
            if (update.contains("UPDATE bf_cultural_after_sale SET status=?")) {
                if (!"REFUNDING".equals(afterSaleStatus)) return 0;
                afterSaleStatus = (String) args[0];
                return 1;
            }
            if (update.startsWith("UPDATE bf_refund SET failure_reason=?")) {
                failureReason = (String) args[0];
                return "PROCESSING".equals(refundStatus) ? 1 : 0;
            }
            if (update.contains("INSERT IGNORE INTO bf_cultural_after_sale_log")
                    && update.contains("CHANNEL_REFUND_ABNORMAL")) {
                if (abnormalLogged) return 0;
                abnormalLogged = true;
                return 1;
            }
            if (update.startsWith("INSERT INTO bf_audit_log")) {
                String action = String.valueOf(args[1]);
                if (action.endsWith("SUCCESS")) successAuditCount++;
                else if (action.endsWith("CLOSED")) closedAuditCount++;
                else if (action.endsWith("ABNORMAL")) abnormalAuditCount++;
                return 1;
            }
            if (update.startsWith("INSERT IGNORE INTO bf_refund_status_log")
                    || update.startsWith("INSERT IGNORE INTO bf_order_status_log")
                    || update.startsWith("INSERT IGNORE INTO bf_cultural_after_sale_log")
                    || update.startsWith("INSERT INTO bf_notification")) {
                return 1;
            }
            throw new AssertionError("Unexpected cultural refund update: " + update);
        }
    }
}
