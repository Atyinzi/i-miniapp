package com.iminiapp.backend.identity.web;

import com.iminiapp.backend.identity.api.LoginResponse;
import com.iminiapp.backend.identity.api.MockLoginRequest;
import com.iminiapp.backend.identity.application.MockLoginService;
import com.iminiapp.backend.identity.application.AccessTokenService;
import com.iminiapp.backend.identity.domain.AccessSession;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;
import com.iminiapp.backend.identity.infrastructure.InMemoryAccessTokenService;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationInterceptorTests {

    private final InMemoryAccessTokenService tokenService = new InMemoryAccessTokenService();
    private final MockLoginService loginService = new MockLoginService(tokenService);
    private final AuthenticationInterceptor interceptor = new AuthenticationInterceptor(tokenService);

    @Test
    void 用户令牌可以访问用户端接口() {
        LoginResponse login = loginService.login(new MockLoginRequest("殷子聪", "USER"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/app/access/me");
        request.addHeader("Authorization", "Bearer " + login.accessToken());

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertNotNull(request.getAttribute(AuthenticationInterceptor.PRINCIPAL_ATTRIBUTE));
    }

    @Test
    void 拒绝用户令牌访问管理端接口() {
        LoginResponse login = loginService.login(new MockLoginRequest("普通用户", "USER"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/access/me");
        request.addHeader("Authorization", "Bearer " + login.accessToken());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("AUTH_403_001", exception.errorCode().code());
    }

    @Test
    void 已停用微信账号即使令牌尚未过期也会被拒绝并吊销令牌() {
        AccessTokenService tokens = mock(AccessTokenService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IdentityPrincipal principal = new IdentityPrincipal(
                "wx-internal", "停用用户", "USER", List.of("ROLE_USER"),
                "WECHAT", "openid-disabled", List.of());
        when(tokens.find("disabled-token")).thenReturn(new AccessSession(
                "disabled-token", "Bearer", OffsetDateTime.now().plusHours(1), principal));
        when(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'",
                Integer.class, "openid-disabled")).thenReturn(0);
        AuthenticationInterceptor secured = new AuthenticationInterceptor(tokens, jdbc);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/app/access/me");
        request.addHeader("Authorization", "Bearer disabled-token");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> secured.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("AUTH_401_001", exception.errorCode().code());
        verify(tokens).revoke("disabled-token");
    }
}
