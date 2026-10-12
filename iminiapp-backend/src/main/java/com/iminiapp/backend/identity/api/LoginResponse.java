package com.iminiapp.backend.identity.api;

import com.iminiapp.backend.identity.domain.AccessSession;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;

import java.time.OffsetDateTime;
import java.util.List;

public record LoginResponse(
        String accessToken,
        String tokenType,
        OffsetDateTime expiresAt,
        LoginPrincipal user) {

    public static LoginResponse from(AccessSession session) {
        IdentityPrincipal principal = session.principal();
        return new LoginResponse(
                session.accessToken(),
                session.tokenType(),
                session.expiresAt(),
                new LoginPrincipal(
                        principal.userId(),
                        principal.displayName(),
                        principal.accountType(),
                        principal.roles(),
                        principal.identityProvider(),
                        principal.permissions()));
    }

    public record LoginPrincipal(
            String userId,
            String displayName,
            String accountType,
            List<String> roles,
            String identityProvider,
            List<String> permissions) {
    }
}
