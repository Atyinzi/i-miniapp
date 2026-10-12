package com.iminiapp.backend.identity.domain;

public record WechatCodeSession(
        String openId,
        String unionId,
        String sessionKey) {
}
