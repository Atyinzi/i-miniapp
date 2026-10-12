package com.iminiapp.backend.identity.infrastructure;

import com.iminiapp.backend.identity.application.AccessTokenService;
import com.iminiapp.backend.identity.domain.AccessSession;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "app.auth.token-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryAccessTokenService implements AccessTokenService {

    private static final ZoneOffset CHINA_ZONE_OFFSET = ZoneOffset.ofHours(8);
    private final ConcurrentHashMap<String, AccessSession> sessions = new ConcurrentHashMap<>();

    @Override
    public AccessSession issue(IdentityPrincipal principal) {
        // Token 前缀只表示平台访问令牌，不应暗示身份来源是 Mock。
        String token = "bfa_" + UUID.randomUUID().toString().replace("-", "");
        AccessSession session = new AccessSession(
                token,
                "Bearer",
                OffsetDateTime.now(CHINA_ZONE_OFFSET).plusHours(8),
                principal);
        sessions.put(token, session);
        return session;
    }

    @Override
    public AccessSession find(String accessToken) {
        AccessSession session = sessions.get(accessToken);
        if (session != null && session.expiresAt().isBefore(OffsetDateTime.now(CHINA_ZONE_OFFSET))) {
            sessions.remove(accessToken);
            return null;
        }
        return session;
    }

    @Override
    public void revoke(String accessToken) {
        sessions.remove(accessToken);
    }

    @Override
    public void revokeAll(String userId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().principal().userId().equals(userId));
    }

    @Override
    public void revokeAllByExternalIdentity(String identityProvider, String externalIdentity) {
        sessions.entrySet().removeIf(entry -> {
            IdentityPrincipal principal = entry.getValue().principal();
            return java.util.Objects.equals(identityProvider, principal.identityProvider())
                    && java.util.Objects.equals(externalIdentity, principal.externalIdentity());
        });
    }
}
