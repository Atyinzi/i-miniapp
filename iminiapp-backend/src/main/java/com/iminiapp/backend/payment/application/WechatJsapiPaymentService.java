package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.api.WechatJsapiPrepayResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class WechatJsapiPaymentService {
    private final JdbcTemplate jdbcTemplate;
    private final WechatPayV3Provider provider;
    private final TransactionTemplate transactions;

    public WechatJsapiPaymentService(
            JdbcTemplate jdbcTemplate,
            WechatPayV3Provider provider,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public WechatJsapiPrepayResponse prepay(
            AuthenticatedPrincipal principal,
            long orderId,
            String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        WechatPayV3Provider.Availability availability = provider.availability();
        if (!availability.available()) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, availability.reason());
        }
        PaymentPreparation preparation = transactions.execute(status -> prepare(
                principal, orderId, idempotencyKey));
        if (preparation == null) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Unable to prepare local WeChat Pay transaction");
        }
        PaymentOrder order = preparation.order();
        PendingPayment pending = preparation.pending();
        String outTradeNo = preparation.outTradeNo();
        WechatPayV3Provider.JsapiPrepayResult result;
        boolean alreadyPrepared = StringUtils.hasText(pending.prepayId());
        if (alreadyPrepared) {
            result = provider.buildJsapiInvocation(outTradeNo, pending.prepayId());
        } else {
            // This network request deliberately runs outside the database transaction.
            // A durable PENDING row already exists, so an early callback is still safe.
            result = provider.createJsapiPrepay(
                    new WechatPayV3Provider.JsapiPrepayCommand(
                            outTradeNo,
                            "i某小程序订单 " + order.orderNo(),
                            order.payableAmountCent(),
                            "CNY",
                            order.openId()));
        }
        String prepayId = result.packageValue().startsWith("prepay_id=")
                ? result.packageValue().substring("prepay_id=".length()) : null;
        if (!StringUtils.hasText(prepayId)) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "WeChat Pay returned invalid JSAPI invocation parameters");
        }
        if (!alreadyPrepared) {
            transactions.executeWithoutResult(status -> storePrepayId(outTradeNo, prepayId));
        }
        return new WechatJsapiPrepayResponse(result.outTradeNo(), result.timeStamp(), result.nonceStr(),
                result.packageValue(), result.signType(), result.paySign());
    }

    private PaymentPreparation prepare(
            AuthenticatedPrincipal principal,
            long orderId,
            String idempotencyKey) {
        PaymentOrder order = lockOwnedOrder(principal, orderId);
        if (!"PENDING_PAYMENT".equals(order.status())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Only pending-payment orders can create a WeChat Pay prepay transaction");
        }
        if (order.expiresAt() != null && order.expiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "The order payment window has expired");
        }
        Integer successful = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bf_payment WHERE order_id=? AND status='SUCCESS'",
                Integer.class, orderId);
        if (successful != null && successful > 0) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "The order is already paid");
        }

        String requestedKeyHash = sha256(idempotencyKey);
        List<PendingPayment> active = jdbcTemplate.query("""
                SELECT payment_no,amount_cent,status,
                       JSON_UNQUOTE(JSON_EXTRACT(callback_payload,'$.prepayId')) prepay_id,
                       JSON_UNQUOTE(JSON_EXTRACT(callback_payload,'$.idempotencyKeyHash')) idempotency_key_hash
                FROM bf_payment
                WHERE order_id=? AND channel='WECHAT_PAY' AND status='PENDING'
                ORDER BY id LIMIT 2 FOR UPDATE
                """, (rs, n) -> new PendingPayment(
                rs.getString("payment_no"), rs.getLong("amount_cent"), rs.getString("status"),
                rs.getString("prepay_id"), rs.getString("idempotency_key_hash")), order.id());
        if (active.size() > 1) {
            // Older versions could create one channel order per Idempotency-Key. Do not
            // choose between multiple externally payable orders or create another one.
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Multiple active WeChat Pay transactions require reconciliation; refresh the order status");
        }
        if (!active.isEmpty()) {
            PendingPayment pending = active.get(0);
            validateReusable(order, pending);
            if (!StringUtils.hasText(pending.prepayId())
                    && !requestedKeyHash.equals(pending.idempotencyKeyHash())) {
                // The first request has committed its durable payment row but has not yet
                // saved the channel prepay id. A different key must not issue a second
                // network request; the client can retry after polling the order.
                throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                        "A WeChat Pay transaction is being initialized; poll the order and retry");
            }
            return new PaymentPreparation(order, pending, pending.paymentNo());
        }

        String outTradeNo = outTradeNo(order.id(), idempotencyKey);
        PendingPayment pending = createPendingPayment(order, outTradeNo, requestedKeyHash);
        return new PaymentPreparation(order, pending, outTradeNo);
    }

    private void storePrepayId(String outTradeNo, String prepayId) {
        int changed = jdbcTemplate.update("""
                UPDATE bf_payment
                SET callback_payload=JSON_SET(COALESCE(callback_payload, JSON_OBJECT()),
                    '$.prepayCreated', TRUE, '$.source', 'WECHAT_JSAPI', '$.prepayId', ?)
                WHERE payment_no=? AND channel='WECHAT_PAY' AND status='PENDING'
                """, prepayId, outTradeNo);
        if (changed != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Local WeChat Pay transaction changed before prepay parameters were saved");
        }
    }

    private PaymentOrder lockOwnedOrder(AuthenticatedPrincipal principal, long orderId) {
        List<PaymentOrder> rows = jdbcTemplate.query("""
                SELECT o.id,o.order_no,o.status,o.payable_amount_cent,o.expires_at,u.wechat_openid
                FROM bf_order o JOIN bf_user u ON u.id=o.user_id
                WHERE o.id=? AND u.wechat_openid=? FOR UPDATE
                """, (rs, n) -> new PaymentOrder(
                rs.getLong("id"), rs.getString("order_no"), rs.getString("status"),
                rs.getLong("payable_amount_cent"), rs.getObject("expires_at", LocalDateTime.class),
                rs.getString("wechat_openid")), orderId, principal.databaseOpenId());
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "Order not found or access denied");
        if (!StringUtils.hasText(rows.get(0).openId())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "The user has no usable WeChat openid");
        }
        return rows.get(0);
    }

    private PendingPayment createPendingPayment(PaymentOrder order, String outTradeNo, String idempotencyKeyHash) {
        List<PendingPayment> existing = jdbcTemplate.query("""
                SELECT payment_no,amount_cent,status,
                       JSON_UNQUOTE(JSON_EXTRACT(callback_payload,'$.prepayId')) prepay_id,
                       JSON_UNQUOTE(JSON_EXTRACT(callback_payload,'$.idempotencyKeyHash')) idempotency_key_hash
                FROM bf_payment
                WHERE payment_no=? AND channel='WECHAT_PAY' FOR UPDATE
                """, (rs, n) -> new PendingPayment(
                rs.getString("payment_no"), rs.getLong("amount_cent"), rs.getString("status"),
                rs.getString("prepay_id"), rs.getString("idempotency_key_hash")), outTradeNo);
        if (!existing.isEmpty()) {
            PendingPayment payment = existing.get(0);
            validateReusable(order, payment);
            return payment;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO bf_payment
                    (order_id,payment_no,channel,transaction_id,amount_cent,status,callback_payload)
                    VALUES (?,?,'WECHAT_PAY',NULL,?,'PENDING',
                        JSON_OBJECT('source','WECHAT_JSAPI','idempotencyKeyHash',?))
                    """, order.id(), outTradeNo, order.payableAmountCent(), idempotencyKeyHash);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "A payment transaction for this order is being created; retry with the same request");
        }
        return new PendingPayment(outTradeNo, order.payableAmountCent(), "PENDING", null,
                idempotencyKeyHash);
    }

    private static void validateReusable(PaymentOrder order, PendingPayment payment) {
        if (payment.amountCent() != order.payableAmountCent()) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Existing WeChat Pay transaction amount does not match the order");
        }
        if (!"PENDING".equals(payment.status())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Existing WeChat Pay transaction is not pending");
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (!StringUtils.hasText(key) || key.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    "Idempotency-Key is required and must not exceed 64 characters");
        }
    }

    private static String outTradeNo(long orderId, String idempotencyKey) {
        return "WXBFH" + Long.toUnsignedString(orderId, 36).toUpperCase(java.util.Locale.ROOT)
                + "-" + sha256(idempotencyKey).substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record PaymentOrder(
            long id,
            String orderNo,
            String status,
            long payableAmountCent,
            LocalDateTime expiresAt,
            String openId) {
    }

    private record PendingPayment(
            String paymentNo,
            long amountCent,
            String status,
            String prepayId,
            String idempotencyKeyHash) {
    }

    private record PaymentPreparation(PaymentOrder order, PendingPayment pending, String outTradeNo) {
    }
}
