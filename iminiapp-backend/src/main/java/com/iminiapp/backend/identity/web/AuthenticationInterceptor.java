package com.iminiapp.backend.identity.web;

import com.iminiapp.backend.identity.application.AccessTokenService;
import com.iminiapp.backend.identity.domain.AccessSession;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = AuthenticationInterceptor.class.getName() + ".principal";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessTokenService accessTokenService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AuthenticationInterceptor(AccessTokenService accessTokenService, JdbcTemplate jdbcTemplate) {
        this.accessTokenService = accessTokenService;
        this.jdbcTemplate = jdbcTemplate;
    }

    AuthenticationInterceptor(AccessTokenService accessTokenService) {
        this.accessTokenService = accessTokenService;
        this.jdbcTemplate = null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "缺少有效的 Bearer Token");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        AccessSession session = accessTokenService.find(token);
        if (session == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "登录令牌无效或已过期");
        }

        IdentityPrincipal source = session.principal();
        requireActiveAccount(source, token);
        String requiredAccountType = requiredAccountType(request.getRequestURI());
        if (!requiredAccountType.equals(source.accountType())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN,
                    source.accountType() + " 账号不能访问 " + requiredAccountType + " 端接口");
        }

        request.setAttribute(PRINCIPAL_ATTRIBUTE, new AuthenticatedPrincipal(
                source.userId(), source.displayName(), source.accountType(), source.roles(),
                source.identityProvider(), source.externalIdentity(), source.permissions()));
        return true;
    }

    private void requireActiveAccount(IdentityPrincipal source, String token) {
        if (jdbcTemplate == null || "MOCK".equals(source.identityProvider())) return;
        Integer active;
        if ("USER".equals(source.accountType())) {
            active = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'",
                    Integer.class, source.externalIdentity());
        } else if ("ADMIN".equals(source.accountType())) {
            active = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM bf_user u JOIN bf_admin_account a ON a.user_id=u.id
                    WHERE u.id=? AND u.status='ACTIVE' AND a.status='ACTIVE'
                    """, Integer.class, numericUserId(source.userId()));
        } else {
            active = 1;
        }
        if (active == null || active == 0) {
            accessTokenService.revoke(token);
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "账号已停用或注销，请重新登录");
        }
    }

    private long numericUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (RuntimeException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "登录身份无效，请重新登录");
        }
    }

    private String requiredAccountType(String requestUri) {
        if (requestUri.startsWith("/api/v1/admin/")) {
            return "ADMIN";
        }
        return "USER";
    }
}
