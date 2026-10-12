package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Durable refund outbox. The administrator transaction only enqueues a request;
 * the external call happens afterwards and is safe to retry using out_refund_no.
 */
@Service
public class WechatRefundDispatchService {
    private final JdbcTemplate jdbcTemplate;
    private final WechatPayV3Provider provider;

    public WechatRefundDispatchService(JdbcTemplate jdbcTemplate, WechatPayV3Provider provider) {
        this.jdbcTemplate = jdbcTemplate;
        this.provider = provider;
    }

    public void enqueue(long refundId, WechatPayV3Provider.RefundCommand command) {
        WechatPayV3Provider.Availability availability = provider.availability();
        if (!availability.available()) {
            throw new BusinessException(CommonErrorCode.REFUND_CONFLICT, availability.reason());
        }
        enqueue(refundId, command, null);
    }

    /**
     * Persists an automatic safety refund even while the channel client is
     * temporarily unavailable. A verified payment callback must never be lost just
     * because deployment credentials or the network are unhealthy; the durable row
     * remains visible to operations and will be retried when the provider recovers.
     */
    public void enqueueAutomatic(long refundId, WechatPayV3Provider.RefundCommand command) {
        WechatPayV3Provider.Availability availability = provider.availability();
        enqueue(refundId, command, availability.available() ? null : safeText(availability.reason()));
    }

    private void enqueue(
            long refundId,
            WechatPayV3Provider.RefundCommand command,
            String initialError) {
        jdbcTemplate.update("""
                INSERT INTO bf_wechat_refund_dispatch
                (refund_id,out_refund_no,out_trade_no,transaction_id,refund_amount_cent,
                 total_amount_cent,currency,reason,dispatch_status,next_attempt_at,last_error)
                VALUES (?,?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,?)
                ON DUPLICATE KEY UPDATE refund_id=VALUES(refund_id)
                """, refundId, command.outRefundNo(), command.outTradeNo(), command.transactionId(),
                command.refundAmountCent(), command.totalAmountCent(), command.currency(), command.reason(),
                initialError);
    }

    @Scheduled(fixedDelayString = "${app.wechat-pay-v3.refund-dispatch-interval:60000}")
    @Transactional
    public void dispatchOne() {
        WechatPayV3Provider.Availability availability = provider.availability();
        if (!availability.available()) {
            jdbcTemplate.update("""
                    UPDATE bf_wechat_refund_dispatch
                    SET last_error=?
                    WHERE dispatch_status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP
                    ORDER BY id LIMIT 1
                    """, safeText(availability.reason()));
            return;
        }
        List<PendingRefund> rows = jdbcTemplate.query("""
                SELECT id,refund_id,out_refund_no,out_trade_no,transaction_id,
                       refund_amount_cent,total_amount_cent,currency,reason,attempt_count
                FROM bf_wechat_refund_dispatch
                WHERE dispatch_status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new PendingRefund(
                rs.getLong("id"), rs.getLong("refund_id"), rs.getString("out_refund_no"),
                rs.getString("out_trade_no"), rs.getString("transaction_id"),
                rs.getLong("refund_amount_cent"), rs.getLong("total_amount_cent"),
                rs.getString("currency"), rs.getString("reason"), rs.getInt("attempt_count")));
        if (rows.isEmpty()) return;
        PendingRefund pending = rows.get(0);
        try {
            WechatPayV3Provider.RefundResult result = provider.requestRefund(
                    new WechatPayV3Provider.RefundCommand(
                            pending.outRefundNo(), pending.outTradeNo(), pending.transactionId(),
                            pending.refundAmountCent(), pending.totalAmountCent(),
                            pending.currency(), pending.reason()));
            jdbcTemplate.update("""
                    UPDATE bf_wechat_refund_dispatch
                    SET dispatch_status='SENT',attempt_count=attempt_count+1,
                        channel_refund_id=?,last_error=NULL,sent_at=CURRENT_TIMESTAMP
                    WHERE id=? AND dispatch_status='PENDING'
                    """, result.refundId(), pending.id());
            jdbcTemplate.update("""
                    UPDATE bf_refund SET channel_refund_id=COALESCE(channel_refund_id,?)
                    WHERE id=? AND status='PROCESSING'
                      AND (channel_refund_id IS NULL OR channel_refund_id=?)
                    """, result.refundId(), pending.refundId(), result.refundId());
        } catch (BusinessException exception) {
            jdbcTemplate.update("""
                    UPDATE bf_wechat_refund_dispatch
                    SET attempt_count=attempt_count+1,last_error=?,
                        next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND)
                    WHERE id=? AND dispatch_status='PENDING'
                    """, safeMessage(exception), backoffSeconds(pending.attemptCount() + 1), pending.id());
        }
    }

    private static int backoffSeconds(int attempt) {
        return Math.min(900, 30 * (1 << Math.min(5, Math.max(0, attempt - 1))));
    }

    private static String safeMessage(Exception exception) {
        String value = exception.getMessage();
        if (value == null) return exception.getClass().getSimpleName();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) return "WeChat Pay v3 provider is unavailable";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private record PendingRefund(
            long id,
            long refundId,
            String outRefundNo,
            String outTradeNo,
            String transactionId,
            long refundAmountCent,
            long totalAmountCent,
            String currency,
            String reason,
            int attemptCount) {
    }
}
