package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Applies callbacks for automatic refunds created from verified exceptional
 * captures. This deliberately has no dependency on order fulfilment services.
 */
@Service
public class WechatExceptionalCaptureRefundService {
    private static final Set<String> KINDS =
            Set.of("AUTO_DUPLICATE_CAPTURE", "AUTO_LATE_CAPTURE");

    private final JdbcTemplate jdbcTemplate;

    public WechatExceptionalCaptureRefundService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean supports(String refundKind) {
        return KINDS.contains(refundKind);
    }

    public void handle(long refundId, long paymentId, String refundKind, long operatorId,
                       String currentStatus, String channelStatus, String message) {
        if (!supports(refundKind)) return;
        if (Set.of("AUTO_REFUNDED", "FAILED").contains(currentStatus)) return;
        if (!"PROCESSING".equals(currentStatus)) {
            throw conflict("Automatic safety refund is not waiting for a channel callback");
        }
        String normalized = channelStatus == null ? "" : channelStatus.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SUCCESS", "CLOSED", "ABNORMAL").contains(normalized)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    "Unsupported WeChat Pay refund status");
        }
        String prefix = "AUTO_DUPLICATE_CAPTURE".equals(refundKind) ? "DUPLICATE" : "LATE";
        String safeMessage = safe(message);
        if ("SUCCESS".equals(normalized)) {
            requireRefundUpdate("""
                    UPDATE bf_refund
                    SET status='AUTO_REFUNDED',refunded_at=CURRENT_TIMESTAMP,failure_reason=NULL
                    WHERE id=? AND status='PROCESSING'
                    """, refundId, null);
            updatePayment(paymentId, prefix + "_REFUNDED", "SUCCESS");
            statusLog(refundId, "PROCESSING", "AUTO_REFUNDED", operatorId,
                    "微信异常实付已自动原路退回", "WECHAT-AUTO-REFUND-SUCCESS:" + refundId);
            audit(operatorId, "WECHAT_EXCEPTION_REFUND_RECOVERED", refundId, "AUTO_REFUNDED");
            return;
        }
        if ("CLOSED".equals(normalized)) {
            requireRefundUpdate("""
                    UPDATE bf_refund
                    SET status='FAILED',failure_reason=?
                    WHERE id=? AND status='PROCESSING'
                    """, refundId, safeMessage);
            updatePayment(paymentId, prefix + "_REFUND_FAILED", "CLOSED");
            statusLog(refundId, "PROCESSING", "FAILED", operatorId, safeMessage,
                    "WECHAT-AUTO-REFUND-CLOSED:" + refundId);
            audit(operatorId, "WECHAT_EXCEPTION_REFUND_FAILED", refundId, "FAILED");
            return;
        }
        jdbcTemplate.update("""
                UPDATE bf_refund SET failure_reason=? WHERE id=? AND status='PROCESSING'
                """, safeMessage, refundId);
        updatePayment(paymentId, prefix + "_REFUND_ABNORMAL", "ABNORMAL");
        statusLog(refundId, "PROCESSING", "PROCESSING", operatorId, safeMessage,
                "WECHAT-AUTO-REFUND-ABNORMAL:" + refundId);
        audit(operatorId, "WECHAT_EXCEPTION_REFUND_ABNORMAL", refundId, "PROCESSING");
    }

    private void requireRefundUpdate(String sql, long refundId, String message) {
        int changed = message == null
                ? jdbcTemplate.update(sql, refundId)
                : jdbcTemplate.update(sql, message, refundId);
        if (changed != 1) throw conflict("Automatic safety refund state changed concurrently");
    }

    private void updatePayment(long paymentId, String status, String refundStatus) {
        if (jdbcTemplate.update("""
                UPDATE bf_payment
                SET status=?,callback_payload=JSON_SET(COALESCE(callback_payload,JSON_OBJECT()),
                    '$.automaticRefundStatus',?,'$.automaticRefundUpdatedAt',CURRENT_TIMESTAMP)
                WHERE id=? AND status IN (
                    'DUPLICATE_SUCCESS','LATE_SUCCESS',
                    'DUPLICATE_REFUND_ABNORMAL','LATE_REFUND_ABNORMAL')
                """, status, refundStatus, paymentId) != 1) {
            throw conflict("Exceptional payment state changed while applying its refund callback");
        }
    }

    private void statusLog(long refundId, String from, String to, long operator,
                           String note, String key) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_refund_status_log
                (refund_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,?,?,?,?,?)
                """, refundId, from, to, operator, note, key);
    }

    private void audit(long operator, String action, long refundId, String status) {
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?,'REFUND',?,JSON_OBJECT('status',?))
                """, operator, action, String.valueOf(refundId), status);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(CommonErrorCode.REFUND_CONFLICT, message);
    }
}
