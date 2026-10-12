package com.iminiapp.backend.identity.web;

import java.util.List;

public record AuthenticatedPrincipal(
        String userId,
        String displayName,
        String accountType,
        List<String> roles,
        String identityProvider,
        String externalIdentity,
        List<String> permissions) {

    public AuthenticatedPrincipal(
            String userId, String displayName, String accountType, List<String> roles,
            String identityProvider, String externalIdentity) {
        this(userId, displayName, accountType, roles, identityProvider, externalIdentity, List.of());
    }

    public AuthenticatedPrincipal(
            String userId,
            String displayName,
            String accountType,
            List<String> roles) {
        this(userId, displayName, accountType, roles, "MOCK", null, List.of());
    }

    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    public String databaseOpenId() {
        if ("WECHAT".equals(identityProvider) && externalIdentity != null && !externalIdentity.isBlank()) {
            return externalIdentity;
        }
        return "mock:" + userId;
    }
}
