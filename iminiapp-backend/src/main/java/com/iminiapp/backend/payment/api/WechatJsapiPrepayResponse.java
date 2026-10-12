package com.iminiapp.backend.payment.api;

public record WechatJsapiPrepayResponse(
        String outTradeNo,
        String timeStamp,
        String nonceStr,
        String packageValue,
        String signType,
        String paySign) {
}
