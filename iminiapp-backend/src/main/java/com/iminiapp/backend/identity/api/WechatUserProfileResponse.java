package com.iminiapp.backend.identity.api;

public record WechatUserProfileResponse(
        String userId,
        String nickname,
        String avatarUrl,
        String mobile,
        String identityProvider) {
}
