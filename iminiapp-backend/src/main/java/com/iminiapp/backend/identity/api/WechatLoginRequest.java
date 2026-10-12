package com.iminiapp.backend.identity.api;

public record WechatLoginRequest(String code, String displayName, String avatarUrl) {
}
