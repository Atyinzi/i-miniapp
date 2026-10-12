package com.iminiapp.backend.payment.api;

import com.iminiapp.backend.payment.application.WechatPayCallbackService;
import com.iminiapp.backend.payment.application.WechatPayV3Provider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/callbacks/wechat-pay")
public class WechatPayCallbackController {
    private final WechatPayCallbackService service;

    public WechatPayCallbackController(WechatPayCallbackService service) {
        this.service = service;
    }

    @PostMapping("/payment")
    public ResponseEntity<Void> payment(
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader(value = "Wechatpay-Signature-Type", required = false) String signatureType,
            @RequestBody String rawBody) {
        service.handlePayment(headers(timestamp, nonce, signature, serial, signatureType), rawBody);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refund")
    public ResponseEntity<Void> refund(
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader(value = "Wechatpay-Signature-Type", required = false) String signatureType,
            @RequestBody String rawBody) {
        service.handleRefund(headers(timestamp, nonce, signature, serial, signatureType), rawBody);
        return ResponseEntity.noContent().build();
    }

    private static WechatPayV3Provider.CallbackHeaders headers(
            String timestamp,
            String nonce,
            String signature,
            String serial,
            String signatureType) {
        return new WechatPayV3Provider.CallbackHeaders(timestamp, nonce, signature, serial, signatureType);
    }
}
