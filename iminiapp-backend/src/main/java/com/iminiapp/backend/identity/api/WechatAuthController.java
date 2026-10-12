package com.iminiapp.backend.identity.api;

import com.iminiapp.backend.identity.application.WechatLoginService;
import com.iminiapp.backend.identity.application.WechatPhoneService;
import com.iminiapp.backend.identity.application.WechatUserProfileService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app/auth")
public class WechatAuthController {
    private final WechatLoginService wechatLoginService;
    private final WechatPhoneService wechatPhoneService;
    private final WechatUserProfileService profileService;

    public WechatAuthController(WechatLoginService wechatLoginService,
                                WechatPhoneService wechatPhoneService,
                                WechatUserProfileService profileService) {
        this.wechatLoginService = wechatLoginService;
        this.wechatPhoneService = wechatPhoneService;
        this.profileService = profileService;
    }

    @PostMapping("/wechat-login")
    public ApiResponse<LoginResponse> wechatLogin(
            @RequestBody WechatLoginRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("微信登录成功", wechatLoginService.login(body), TraceIds.from(request));
    }

    @PostMapping("/wechat-phone")
    public ApiResponse<WechatPhoneResponse> wechatPhone(
            @RequestBody WechatPhoneRequest body,
            HttpServletRequest request) {
        AuthenticatedPrincipal principal = CurrentPrincipal.from(request);
        WechatPhoneResponse response = wechatPhoneService.exchange(body.code());
        profileService.updateMobile(principal.databaseOpenId(), response.purePhoneNumber());
        return ApiResponse.success("微信手机号授权成功", response, TraceIds.from(request));
    }
}
