package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.refund.application.RefundApplicationService;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class WechatPayCallbackService {
    private final WechatPayV3Provider provider;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final WechatPaymentSettlementService paymentService;
    private final RefundApplicationService refundService;

    public WechatPayCallbackService(
            WechatPayV3Provider provider,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            WechatPaymentSettlementService paymentService,
            RefundApplicationService refundService) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    @Transactional
    public CallbackResult handlePayment(WechatPayV3Provider.CallbackHeaders headers, String rawBody) {
        WechatPayV3Provider.VerifiedNotification notification =
                provider.verifyAndDecrypt(headers, rawBody);
        if (!"TRANSACTION.SUCCESS".equals(notification.eventType())
                || !"transaction".equalsIgnoreCase(notification.resourceType())) {
            throw invalid("Unexpected WeChat Pay payment notification type");
        }
        if (!register(notification, rawBody)) return new CallbackResult(true);
        JsonNode resource = resource(notification);
        if (!"SUCCESS".equals(required(resource, "trade_state"))) {
            throw invalid("Payment success callback contains a non-success trade state");
        }
        if (!"JSAPI".equals(required(resource, "trade_type"))) {
            throw invalid("Payment callback is not a JSAPI transaction");
        }
        JsonNode amount = resource.path("amount");
        if (!amount.has("total") || !amount.path("total").canConvertToLong()) {
            throw invalid("WeChat Pay callback has no valid total amount");
        }
        if (!"CNY".equals(required(amount, "currency"))) {
            throw invalid("WeChat Pay callback currency is not CNY");
        }
        String outTradeNo = required(resource, "out_trade_no");
        paymentService.settle(
                outTradeNo,
                required(resource, "transaction_id"),
                amount.path("total").longValue(),
                notification.notificationId());
        markProcessed(notification.notificationId(), outTradeNo);
        return new CallbackResult(false);
    }

    @Transactional
    public CallbackResult handleRefund(WechatPayV3Provider.CallbackHeaders headers, String rawBody) {
        WechatPayV3Provider.VerifiedNotification notification =
                provider.verifyAndDecrypt(headers, rawBody);
        if (!notification.eventType().startsWith("REFUND.")
                || !"refund".equalsIgnoreCase(notification.resourceType())) {
            throw invalid("Unexpected WeChat Pay refund notification type");
        }
        if (!register(notification, rawBody)) return new CallbackResult(true);
        JsonNode resource = resource(notification);
        String outRefundNo = required(resource, "out_refund_no");
        String refundId = required(resource, "refund_id");
        String status = required(resource, "refund_status");
        if (!notification.eventType().equals("REFUND." + status)) {
            throw invalid("WeChat Pay refund event type and status do not match");
        }
        JsonNode amount = resource.path("amount");
        if (!amount.path("refund").canConvertToLong() || !amount.path("total").canConvertToLong()) {
            throw invalid("WeChat Pay refund callback amount is incomplete");
        }
        if (!"CNY".equals(required(amount, "currency"))) {
            throw invalid("WeChat Pay refund callback currency is not CNY");
        }
        refundService.callbackFromWechat(refundId, outRefundNo, status,
                amount.path("refund").longValue(), amount.path("total").longValue(),
                "WeChat Pay refund status: " + status);
        markProcessed(notification.notificationId(), outRefundNo);
        return new CallbackResult(false);
    }

    private boolean register(WechatPayV3Provider.VerifiedNotification notification, String rawBody) {
        String payloadHash = sha256(rawBody);
        int changed = jdbcTemplate.update("""
                INSERT IGNORE INTO bf_wechat_pay_callback_event
                (notification_id,event_type,resource_type,payload_hash,process_status)
                VALUES (?,?,?,?,'RECEIVED')
                """, notification.notificationId(), notification.eventType(), notification.resourceType(),
                payloadHash);
        if (changed == 1) return true;
        String existingHash = jdbcTemplate.queryForObject("""
                SELECT payload_hash FROM bf_wechat_pay_callback_event WHERE notification_id=?
                """, String.class, notification.notificationId());
        if (!payloadHash.equals(existingHash)) {
            throw new BusinessException(CommonErrorCode.CONFLICT,
                    "WeChat Pay notification id was reused with a different payload");
        }
        return false;
    }

    private void markProcessed(String notificationId, String reference) {
        jdbcTemplate.update("""
                UPDATE bf_wechat_pay_callback_event
                SET process_status='PROCESSED',business_reference=?,processed_at=CURRENT_TIMESTAMP,
                    failure_reason=NULL
                WHERE notification_id=? AND process_status='RECEIVED'
                """, reference, notificationId);
    }

    private JsonNode resource(WechatPayV3Provider.VerifiedNotification notification) {
        try {
            return objectMapper.readTree(notification.decryptedResourceJson());
        } catch (Exception exception) {
            throw invalid("Invalid decrypted WeChat Pay resource");
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (!StringUtils.hasText(value)) throw invalid("Missing WeChat Pay resource field: " + field);
        return value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record CallbackResult(boolean duplicate) {
    }
}
