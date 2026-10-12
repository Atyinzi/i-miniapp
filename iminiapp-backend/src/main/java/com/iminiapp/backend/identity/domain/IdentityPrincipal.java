package com.iminiapp.backend.identity.domain;

import java.util.List;

public record IdentityPrincipal(
        String userId,
        String displayName,
        String accountType,
        List<String> roles,
        String identityProvider,
        String externalIdentity,
        List<String> permissions) {

    public IdentityPrincipal(
            String userId,
            String displayName,
            String accountType,
            List<String> roles,
            String identityProvider,
            String externalIdentity) {
        this(userId, displayName, accountType, roles, identityProvider, externalIdentity, List.of());
    }

    public IdentityPrincipal(
            String userId,
            String displayName,
            String accountType,
            List<String> roles) {
        this(userId, displayName, accountType, roles, "MOCK", null, List.of());
    }
}
