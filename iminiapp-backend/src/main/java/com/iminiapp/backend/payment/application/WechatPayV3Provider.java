package com.iminiapp.backend.payment.application;

/**
 * Boundary around WeChat Pay API v3. Business services depend on this interface so
 * the real network client can stay disabled until production credentials are ready.
 */
public interface WechatPayV3Provider {

    Availability availability();

    JsapiPrepayResult createJsapiPrepay(JsapiPrepayCommand command);

    JsapiPrepayResult buildJsapiInvocation(String outTradeNo, String prepayId);

    RefundResult requestRefund(RefundCommand command);

    VerifiedNotification verifyAndDecrypt(CallbackHeaders headers, String rawBody);

    record Availability(boolean available, String reason) {
    }

    record JsapiPrepayCommand(
            String outTradeNo,
            String description,
            long amountCent,
            String currency,
            String payerOpenId) {
    }

    record JsapiPrepayResult(
            String outTradeNo,
            String timeStamp,
            String nonceStr,
            String packageValue,
            String signType,
            String paySign) {
    }

    record RefundCommand(
            String outRefundNo,
            String outTradeNo,
            String transactionId,
            long refundAmountCent,
            long totalAmountCent,
            String currency,
            String reason) {
    }

    record RefundResult(String refundId, String status) {
    }

    record CallbackHeaders(
            String timestamp,
            String nonce,
            String signature,
            String serial,
            String signatureType) {
    }

    record VerifiedNotification(
            String notificationId,
            String eventType,
            String resourceType,
            String decryptedResourceJson) {
    }
}
