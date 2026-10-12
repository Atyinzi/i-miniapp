package com.iminiapp.backend.gate.application;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class GateSignature {
    private GateSignature() { }

    /**
     * 签名原文：deviceNo + LF + timestamp + LF + nonce + LF + eventNo + LF + normalizedCode。
     */
    public static String calculate(String secret, String deviceNo, long timestamp, String nonce,
                                   String eventNo, String normalizedCode) {
        try {
            String payload = deviceNo + "\n" + timestamp + "\n" + nonce + "\n" + eventNo + "\n" + normalizedCode;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算闸机请求签名", ex);
        }
    }
}
