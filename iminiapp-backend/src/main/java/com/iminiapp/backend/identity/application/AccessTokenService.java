package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.domain.AccessSession;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;

public interface AccessTokenService {

    AccessSession issue(IdentityPrincipal principal);

    AccessSession find(String accessToken);

    void revoke(String accessToken);

    void revokeAll(String userId);

    void revokeAllByExternalIdentity(String identityProvider, String externalIdentity);
}
