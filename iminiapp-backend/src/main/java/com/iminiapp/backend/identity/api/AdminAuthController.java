package com.iminiapp.backend.identity.api;

import com.iminiapp.backend.identity.application.AccessTokenService;
import com.iminiapp.backend.identity.application.AdminLoginService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {
    private final AdminLoginService loginService;
    private final AccessTokenService accessTokenService;

    public AdminAuthController(AdminLoginService loginService, AccessTokenService accessTokenService) {
        this.loginService = loginService;
        this.accessTokenService = accessTokenService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody AdminLoginRequest body, HttpServletRequest request) {
        return ApiResponse.success("登录成功", loginService.login(body, request.getRemoteAddr()), TraceIds.from(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization, HttpServletRequest request) {
        accessTokenService.revoke(authorization.substring("Bearer ".length()).trim());
        return ApiResponse.success("已安全退出", null, TraceIds.from(request));
    }

    @GetMapping("/me")
    public ApiResponse<Object> me(HttpServletRequest request) {
        return ApiResponse.success(CurrentPrincipal.from(request), TraceIds.from(request));
    }
}
