package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.api.LoginResponse;
import com.iminiapp.backend.identity.api.WechatLoginRequest;
import com.iminiapp.backend.identity.domain.AccessSession;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;
import com.iminiapp.backend.identity.domain.WechatCodeSession;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class WechatLoginService {

    private final WechatCodeSessionGateway codeSessionGateway;
    private final AccessTokenService accessTokenService;
    private final WechatUserProfileService profileService;

    @Autowired
    public WechatLoginService(
            WechatCodeSessionGateway codeSessionGateway,
            AccessTokenService accessTokenService,
            WechatUserProfileService profileService) {
        this.codeSessionGateway = codeSessionGateway;
        this.accessTokenService = accessTokenService;
        this.profileService = profileService;
    }

    WechatLoginService(
            WechatCodeSessionGateway codeSessionGateway,
            AccessTokenService accessTokenService) {
        this(codeSessionGateway, accessTokenService, null);
    }

    public LoginResponse login(WechatLoginRequest request) {
        if (request == null || !StringUtils.hasText(request.code())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "微信登录 code 不能为空");
        }
        String displayName = normalizeDisplayName(request.displayName());
        WechatCodeSession codeSession = codeSessionGateway.exchange(request.code().trim());
        if (profileService != null) {
            profileService.recordLogin(codeSession.openId(), codeSession.unionId(), displayName, request.avatarUrl());
        }
        String internalUserId = "wx-" + UUID.nameUUIDFromBytes(
                ("WECHAT:" + codeSession.openId()).getBytes(StandardCharsets.UTF_8));
        IdentityPrincipal principal = new IdentityPrincipal(
                internalUserId,
                displayName,
                "USER",
                List.of("ROLE_USER"),
                "WECHAT",
                codeSession.openId());
        AccessSession accessSession = accessTokenService.issue(principal);
        return LoginResponse.from(accessSession);
    }

    private String normalizeDisplayName(String value) {
        if (!StringUtils.hasText(value)) {
            return "微信用户";
        }
        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "displayName 最长为 64 个字符");
        }
        return normalized;
    }
}
