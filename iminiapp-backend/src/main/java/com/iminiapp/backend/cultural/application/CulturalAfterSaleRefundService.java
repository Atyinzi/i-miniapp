package com.iminiapp.backend.cultural.application;

import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * Applies a verified WeChat Pay refund callback to a cultural-store after-sale.
 *
 * <p>This projection is deliberately separate from the ordinary order-refund
 * projection: cultural after-sales own shipment and stock transitions which must
 * not run for scenic, sport, dining, hotel, or vehicle orders.</p>
 */
@Service
public class CulturalAfterSaleRefundService {
    private static final Set<String> RETRYABLE_PREVIOUS_ORDER_STATES =
            Set.of("PAID", "PREPARING", "SHIPPED", "COMPLETED");

    private final JdbcTemplate jdbc;

    public CulturalAfterSaleRefundService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} when the refund belongs to a cultural after-sale;
     * otherwise {@code false}, allowing the caller to use its ordinary projection.
     */
    @Transactional
    public boolean handleWechatCallback(long refundId, String channelStatus, String message) {
        List<CulturalRefund> rows = jdbc.query("""
                SELECT a.id,a.order_id,o.order_no,o.status order_status,
                       a.after_sale_type,a.status after_sale_status,a.refund_amount_cent,
                       a.requested_by,a.reviewed_by,r.status refund_status,
                       r.previous_order_status
                FROM bf_cultural_after_sale a
                JOIN bf_order o ON o.id=a.order_id
                JOIN bf_refund r ON r.id=a.refund_id
                WHERE a.refund_id=?
                FOR UPDATE
                """, (rs, n) -> new CulturalRefund(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no"),
                rs.getString("order_status"), rs.getString("after_sale_type"),
                rs.getString("after_sale_status"), rs.getLong("refund_amount_cent"),
                rs.getLong("requested_by"), rs.getObject("reviewed_by", Long.class),
                rs.getString("refund_status"), rs.getString("previous_order_status")), refundId);
        if (rows.isEmpty()) return false;

        CulturalRefund refund = rows.get(0);
        long operator = refund.reviewedBy() == null ? refund.requestedBy() : refund.reviewedBy();
        switch (channelStatus) {
            case "SUCCESS" -> complete(refundId, refund, operator);
            case "CLOSED" -> closeForRetry(refundId, refund, operator, message);
            case "ABNORMAL" -> markAbnormal(refundId, refund, operator, message);
            default -> throw conflict("不支持的微信退款状态");
        }
        return true;
    }

    private void complete(long refundId, CulturalRefund refund, long operator) {
        if ("SUCCESS".equals(refund.refundStatus()) && "COMPLETED".equals(refund.afterSaleStatus())) {
            return;
        }
        requireProcessing(refund);
        restoreStock(refund.orderId(), refund.orderNo(), refund.id(), operator);

        if (jdbc.update("""
                UPDATE bf_refund
                SET status='SUCCESS',refunded_at=CURRENT_TIMESTAMP,failure_reason=NULL
                WHERE id=? AND status='PROCESSING'
                """, refundId) != 1) {
            throw conflict("文创退款状态已变化");
        }
        if (jdbc.update("""
                UPDATE bf_order SET status='REFUNDED'
                WHERE id=? AND status='REFUNDING'
                """, refund.orderId()) != 1) {
            throw conflict("文创订单状态已变化，退款结果尚未落账");
        }
        if (jdbc.update("""
                UPDATE bf_cultural_after_sale
                SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP
                WHERE id=? AND refund_id=? AND status='REFUNDING'
                """, refund.id(), refundId) != 1) {
            throw conflict("文创售后状态已变化，退款结果尚未落账");
        }
        // A refund-only order may still have an unshipped placeholder. It is safe
        // to cancel only after WeChat confirms the refund; CLOSED can then restore
        // the original order without leaving a cancelled shipment behind.
        jdbc.update("UPDATE bf_shipment SET status='CANCELLED' WHERE order_id=? AND status='PENDING'",
                refund.orderId());
        jdbc.update("""
                INSERT IGNORE INTO bf_refund_status_log
                (refund_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'PROCESSING','SUCCESS',?,'微信支付退款成功',?)
                """, refundId, operator, "CULTURAL-WECHAT-REFUND-SUCCESS:" + refundId);
        jdbc.update("""
                INSERT IGNORE INTO bf_order_status_log
                (order_id,from_status,to_status,operator_id,reason,idempotency_key)
                VALUES (?,'REFUNDING','REFUNDED',?,'微信支付文创售后退款成功',?)
                """, refund.orderId(), operator, "CULTURAL-REFUND-COMPLETE:" + refund.id());
        log(refund.id(), "CHANNEL_REFUND_SUCCESS", "REFUNDING", "COMPLETED", operator,
                "微信支付退款成功", "CULTURAL-CHANNEL-SUCCESS:" + refundId);
        notifyUser(refund.requestedBy(), refund.orderId(), "退款完成",
                "售后退款已原路退回，金额为" + refund.amountCent() + "分");
        audit(operator, "CULTURAL_AFTER_SALE_CHANNEL_REFUND_SUCCESS", refund.id(), "COMPLETED");
    }

    private void closeForRetry(long refundId, CulturalRefund refund, long operator, String message) {
        if ("FAILED".equals(refund.refundStatus()) && !"REFUNDING".equals(refund.afterSaleStatus())) {
            return;
        }
        requireProcessing(refund);
        if (!RETRYABLE_PREVIOUS_ORDER_STATES.contains(refund.previousOrderStatus())) {
            throw conflict("文创退款缺少可恢复的原订单状态");
        }
        String failure = safeMessage(message, "微信支付退款已关闭");
        if (jdbc.update("""
                UPDATE bf_refund SET status='FAILED',failure_reason=?
                WHERE id=? AND status='PROCESSING'
                """, failure, refundId) != 1) {
            throw conflict("文创退款状态已变化");
        }
        if (jdbc.update("UPDATE bf_order SET status=? WHERE id=? AND status='REFUNDING'",
                refund.previousOrderStatus(), refund.orderId()) != 1) {
            throw conflict("文创订单状态已变化，无法恢复退款前状态");
        }
        String retryStatus = "REFUND_FAILED";
        if (jdbc.update("""
                UPDATE bf_cultural_after_sale SET status=?
                WHERE id=? AND refund_id=? AND status='REFUNDING'
                """, retryStatus, refund.id(), refundId) != 1) {
            throw conflict("文创售后状态已变化，无法恢复退款前状态");
        }
        jdbc.update("""
                INSERT IGNORE INTO bf_refund_status_log
                (refund_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'PROCESSING','FAILED',?,?,?)
                """, refundId, operator, failure, "CULTURAL-WECHAT-REFUND-CLOSED:" + refundId);
        jdbc.update("""
                INSERT IGNORE INTO bf_order_status_log
                (order_id,from_status,to_status,operator_id,reason,idempotency_key)
                VALUES (?,'REFUNDING',?,?,?,?)
                """, refund.orderId(), refund.previousOrderStatus(), operator,
                "微信支付退款关闭，恢复原业务状态", "CULTURAL-REFUND-RESTORE:" + refundId);
        log(refund.id(), "CHANNEL_REFUND_CLOSED", "REFUNDING", retryStatus, operator,
                failure, "CULTURAL-CHANNEL-CLOSED:" + refundId);
        notifyUser(refund.requestedBy(), refund.orderId(), "退款暂未完成",
                "支付渠道已关闭本次退款，工作人员将重新处理");
        audit(operator, "CULTURAL_AFTER_SALE_CHANNEL_REFUND_CLOSED", refund.id(), retryStatus);
    }

    private void markAbnormal(long refundId, CulturalRefund refund, long operator, String message) {
        requireProcessing(refund);
        String failure = safeMessage(message, "微信支付退款状态异常，等待渠道最终结果");
        jdbc.update("UPDATE bf_refund SET failure_reason=? WHERE id=? AND status='PROCESSING'",
                failure, refundId);
        int logged = jdbc.update("""
                INSERT IGNORE INTO bf_cultural_after_sale_log
                (after_sale_id,action,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'CHANNEL_REFUND_ABNORMAL','REFUNDING','REFUNDING',?,?,?)
                """, refund.id(), operator, failure, "CULTURAL-CHANNEL-ABNORMAL:" + refundId);
        if (logged == 1) {
            audit(operator, "CULTURAL_AFTER_SALE_CHANNEL_REFUND_ABNORMAL", refund.id(), "REFUNDING");
        }
    }

    private void restoreStock(long orderId, String orderNo, long caseId, long operator) {
        List<StockItem> items = jdbc.query("""
                SELECT id,sku_id,quantity FROM bf_order_item
                WHERE order_id=? AND resource_type='CULTURAL_PRODUCT' ORDER BY id
                """, (rs, n) -> new StockItem(
                rs.getLong("id"), rs.getLong("sku_id"), rs.getInt("quantity")), orderId);
        if (items.isEmpty()) throw conflict("文创订单商品不存在，无法完成退款入库");
        for (StockItem item : items) {
            String key = "AFTER-SALE-RETURN:" + caseId + ":" + item.itemId();
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bf_cultural_stock_log WHERE idempotency_key=?", Integer.class, key);
            if (exists != null && exists > 0) continue;
            if (jdbc.update("""
                    UPDATE bf_cultural_stock
                    SET on_hand_quantity=on_hand_quantity+?,available_quantity=available_quantity+?,version=version+1
                    WHERE sku_id=?
                    """, item.quantity(), item.quantity(), item.skuId()) != 1) {
                throw conflict("商品库存记录不存在，无法完成退款入库");
            }
            jdbc.update("""
                    INSERT INTO bf_cultural_stock_log
                    (sku_id,change_type,quantity_delta,on_hand_after,available_after,
                     idempotency_key,operator_id,reference_no,remark)
                    SELECT ?,'RETURN',?,on_hand_quantity,available_quantity,?,?,?,
                           '微信支付退款成功后售后入库'
                    FROM bf_cultural_stock WHERE sku_id=?
                    """, item.skuId(), item.quantity(), key, operator, orderNo, item.skuId());
        }
    }

    private void requireProcessing(CulturalRefund refund) {
        if (!"PROCESSING".equals(refund.refundStatus())
                || !"REFUNDING".equals(refund.afterSaleStatus())
                || !"REFUNDING".equals(refund.orderStatus())) {
            throw conflict("文创售后当前状态不能接收退款结果");
        }
    }

    private void log(long id, String action, String from, String to, Long operator, String note, String key) {
        jdbc.update("""
                INSERT IGNORE INTO bf_cultural_after_sale_log
                (after_sale_id,action,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,?,?,?,?,?,?)
                """, id, action, from, to, operator, note, key);
    }

    private void notifyUser(long userId, long orderId, String title, String content) {
        jdbc.update("""
                INSERT INTO bf_notification
                (user_id,notification_type,title,content,business_type,business_id)
                VALUES (?,'ORDER',?,?,'CULTURAL_AFTER_SALE',?)
                """, userId, title, content, orderId);
    }

    private void audit(long user, String action, long id, String status) {
        jdbc.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?,'CULTURAL_AFTER_SALE',?,JSON_OBJECT('status',?))
                """, user, action, String.valueOf(id), status);
    }

    private static String safeMessage(String value, String fallback) {
        String result = StringUtils.hasText(value) ? value.trim() : fallback;
        return result.substring(0, Math.min(500, result.length()));
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(CommonErrorCode.REFUND_CONFLICT, message);
    }

    private record CulturalRefund(
            long id,
            long orderId,
            String orderNo,
            String orderStatus,
            String type,
            String afterSaleStatus,
            long amountCent,
            long requestedBy,
            Long reviewedBy,
            String refundStatus,
            String previousOrderStatus) {
    }

    private record StockItem(long itemId, long skuId, int quantity) {
    }
}
