package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.api.WechatJsapiPrepayResponse;
import com.iminiapp.backend.payment.application.WechatPayV3Provider;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class VehicleSupplementWechatPaymentService {
    private final JdbcTemplate jdbcTemplate;
    private final WechatPayV3Provider provider;
    private final TransactionTemplate transactions;

    public VehicleSupplementWechatPaymentService(JdbcTemplate jdbcTemplate,
            WechatPayV3Provider provider, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public WechatJsapiPrepayResponse prepay(AuthenticatedPrincipal principal, long orderId,
            String idempotencyKey) {
        validateKey(idempotencyKey);
        WechatPayV3Provider.Availability availability = provider.availability();
        if (!availability.available()) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, availability.reason());
        }
        Preparation preparation = transactions.execute(status -> prepare(principal, orderId, idempotencyKey));
        if (preparation == null) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "无法准备车辆补款支付单");
        }
        PendingPayment payment = preparation.payment();
        WechatPayV3Provider.JsapiPrepayResult result = StringUtils.hasText(payment.prepayId())
                ? provider.buildJsapiInvocation(payment.paymentNo(), payment.prepayId())
                : provider.createJsapiPrepay(new WechatPayV3Provider.JsapiPrepayCommand(
                        payment.paymentNo(), "i某小程序车辆结算补款 " + preparation.orderNo(),
                        preparation.amountCent(), "CNY", preparation.openId()));
        String prepayId = result.packageValue().startsWith("prepay_id=")
                ? result.packageValue().substring("prepay_id=".length()) : null;
        if (!StringUtils.hasText(prepayId)) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "微信支付返回的预支付参数无效");
        }
        if (!StringUtils.hasText(payment.prepayId())) {
            transactions.executeWithoutResult(status -> storePrepayId(payment.paymentNo(), prepayId));
        }
        return new WechatJsapiPrepayResponse(result.outTradeNo(), result.timeStamp(), result.nonceStr(),
                result.packageValue(), result.signType(), result.paySign());
    }

    private Preparation prepare(AuthenticatedPrincipal principal, long orderId, String key) {
        List<SettlementOrder> rows = jdbcTemplate.query("""
                SELECT s.id settlement_id,s.status settlement_status,s.supplement_amount_cent,
                       s.supplement_paid_cent,o.order_no,o.status order_status,u.wechat_openid
                FROM bf_vehicle_settlement s JOIN bf_order o ON o.id=s.order_id
                JOIN bf_user u ON u.id=o.user_id
                WHERE s.order_id=? AND o.order_type='VEHICLE_RENTAL' AND u.wechat_openid=?
                FOR UPDATE
                """, (rs, n) -> new SettlementOrder(rs.getLong("settlement_id"),
                rs.getString("settlement_status"), rs.getLong("supplement_amount_cent"),
                rs.getLong("supplement_paid_cent"), rs.getString("order_no"),
                rs.getString("order_status"), rs.getString("wechat_openid")),
                orderId, principal.databaseOpenId());
        if (rows.isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "车辆结算单不存在或无权访问");
        }
        SettlementOrder order = rows.get(0);
        long outstanding = order.supplementAmountCent() - order.supplementPaidCent();
        if (!"PENDING_SUPPLEMENT".equals(order.status())
                || !"PENDING_SUPPLEMENT".equals(order.orderStatus()) || outstanding <= 0) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆结算单当前无需补款");
        }
        if (!StringUtils.hasText(order.openId())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "当前用户没有可用的微信 openid");
        }
        String keyHash = sha256(key);
        List<PendingPayment> existing = jdbcTemplate.query("""
                SELECT payment_no,amount_cent,status,
                       JSON_UNQUOTE(JSON_EXTRACT(callback_payload,'$.prepayId')) prepay_id,
                       JSON_UNQUOTE(JSON_EXTRACT(callback_payload,'$.idempotencyKeyHash')) key_hash
                FROM bf_payment WHERE vehicle_settlement_id=? AND channel='WECHAT_PAY' FOR UPDATE
                """, (rs, n) -> new PendingPayment(rs.getString("payment_no"),
                rs.getLong("amount_cent"), rs.getString("status"), rs.getString("prepay_id"),
                rs.getString("key_hash")), order.id());
        if (!existing.isEmpty()) {
            PendingPayment payment = existing.get(0);
            if (!"PENDING".equals(payment.status()) || payment.amountCent() != outstanding) {
                throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆补款支付单状态或金额已变化");
            }
            if (!StringUtils.hasText(payment.prepayId()) && !keyHash.equals(payment.keyHash())) {
                throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆补款支付单正在初始化，请稍后重试");
            }
            return new Preparation(order.orderNo(), order.openId(), outstanding, payment);
        }
        String paymentNo = "WXVS" + Long.toUnsignedString(order.id(), 36).toUpperCase(Locale.ROOT)
                + "-" + keyHash.substring(0, 12).toUpperCase(Locale.ROOT);
        try {
            jdbcTemplate.update("""
                    INSERT INTO bf_payment
                    (order_id,vehicle_settlement_id,payment_no,channel,amount_cent,status,callback_payload)
                    VALUES (?, ?,?,'WECHAT_PAY',?,'PENDING',JSON_OBJECT(
                        'source','VEHICLE_SUPPLEMENT_WECHAT_JSAPI',
                        'paymentPurpose','VEHICLE_SUPPLEMENT','idempotencyKeyHash',?))
                    """, orderId, order.id(), paymentNo, outstanding, keyHash);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆补款支付单正在创建，请使用同一请求重试");
        }
        return new Preparation(order.orderNo(), order.openId(), outstanding,
                new PendingPayment(paymentNo, outstanding, "PENDING", null, keyHash));
    }

    private void storePrepayId(String paymentNo, String prepayId) {
        int changed = jdbcTemplate.update("""
                UPDATE bf_payment SET callback_payload=JSON_SET(
                    COALESCE(callback_payload,JSON_OBJECT()),'$.prepayId',?,'$.prepayCreated',TRUE)
                WHERE payment_no=? AND vehicle_settlement_id IS NOT NULL
                  AND channel='WECHAT_PAY' AND status='PENDING'
                """, prepayId, paymentNo);
        if (changed != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆补款支付单已发生变化");
        }
    }

    private static void validateKey(String key) {
        if (!StringUtils.hasText(key) || key.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    "Idempotency-Key 必填且不能超过 64 个字符");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record SettlementOrder(long id, String status, long supplementAmountCent,
                                   long supplementPaidCent, String orderNo, String orderStatus,
                                   String openId) {}
    private record PendingPayment(String paymentNo, long amountCent, String status,
                                  String prepayId, String keyHash) {}
    private record Preparation(String orderNo, String openId, long amountCent,
                               PendingPayment payment) {}
}
