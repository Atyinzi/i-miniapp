package com.iminiapp.backend.identity.api;

import com.iminiapp.backend.identity.application.AccessTokenService;
import com.iminiapp.backend.identity.application.AccountService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/app/auth")
public class AccountController {
    private static final String BEARER = "Bearer ";
    private final AccessTokenService tokens;
    private final AccountService accounts;

    public AccountController(AccessTokenService tokens, AccountService accounts) {
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        tokens.revoke(token(request));
        return ApiResponse.success("已安全退出", null, TraceIds.from(request));
    }

    @DeleteMapping("/account")
    public ApiResponse<Void> cancel(@RequestBody AccountCancellationRequest body, HttpServletRequest request) {
        AuthenticatedPrincipal principal = CurrentPrincipal.from(request);
        accounts.cancel(principal, body);
        tokens.revokeAll(principal.userId());
        return ApiResponse.success("账号已注销", null, TraceIds.from(request));
    }

    private String token(HttpServletRequest request) {
        String value = request.getHeader("Authorization");
        return value != null && value.startsWith(BEARER) ? value.substring(BEARER.length()).trim() : "";
    }
}
