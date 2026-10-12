package com.iminiapp.backend.identity.api;

import com.iminiapp.backend.identity.application.WechatUserProfileService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app/profile")
public class WechatUserProfileController {
    private final WechatUserProfileService profileService;

    public WechatUserProfileController(WechatUserProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ApiResponse<WechatUserProfileResponse> profile(HttpServletRequest request) {
        AuthenticatedPrincipal principal = CurrentPrincipal.from(request);
        return ApiResponse.success(profileService.profile(
                principal.userId(), requireWechat(principal)), TraceIds.from(request));
    }

    @PutMapping
    public ApiResponse<WechatUserProfileResponse> update(
            @RequestBody WechatUserProfileUpdateRequest body,
            HttpServletRequest request) {
        AuthenticatedPrincipal principal = CurrentPrincipal.from(request);
        return ApiResponse.success("微信用户资料已更新", profileService.updateProfile(
                principal.userId(), requireWechat(principal), body.nickname(), body.avatarUrl()), TraceIds.from(request));
    }

    private String requireWechat(AuthenticatedPrincipal principal) {
        if (!"WECHAT".equals(principal.identityProvider()) || principal.externalIdentity() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "仅微信用户可以访问微信资料");
        }
        return principal.externalIdentity();
    }
}
