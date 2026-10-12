package com.iminiapp.backend.identity.infrastructure;

import com.iminiapp.backend.identity.application.AccessTokenService;
import com.iminiapp.backend.identity.domain.AccessSession;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.auth.token-store", havingValue = "redis")
public class RedisAccessTokenService implements AccessTokenService {

    private static final String KEY_PREFIX = "bfh:auth:token:";
    private static final String ROLE_SEPARATOR = "\u001f";
    private static final ZoneOffset CHINA_ZONE_OFFSET = ZoneOffset.ofHours(8);

    private final StringRedisTemplate redisTemplate;
    private final Duration tokenTtl;

    public RedisAccessTokenService(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.token-ttl:8h}") Duration tokenTtl) {
        this.redisTemplate = redisTemplate;
        this.tokenTtl = tokenTtl;
    }

    @Override
    public AccessSession issue(IdentityPrincipal principal) {
        String token = "bfa_" + UUID.randomUUID().toString().replace("-", "");
        AccessSession session = new AccessSession(
                token,
                "Bearer",
                OffsetDateTime.now(CHINA_ZONE_OFFSET).plus(tokenTtl),
                principal);
        String redisKey = key(token);
        redisTemplate.opsForHash().putAll(redisKey, Map.of(
                "tokenType", session.tokenType(),
                "expiresAt", session.expiresAt().toString(),
                "userId", principal.userId(),
                "displayName", principal.displayName(),
                "accountType", principal.accountType(),
                "identityProvider", principal.identityProvider(),
                "externalIdentity", principal.externalIdentity() == null ? "" : principal.externalIdentity(),
                "roles", String.join(ROLE_SEPARATOR, principal.roles()),
                "permissions", String.join(ROLE_SEPARATOR, principal.permissions())));
        redisTemplate.expire(redisKey, tokenTtl);
        redisTemplate.opsForSet().add(userTokensKey(principal.userId()), token);
        redisTemplate.expire(userTokensKey(principal.userId()), tokenTtl);
        if (principal.externalIdentity() != null && !principal.externalIdentity().isBlank()) {
            String identityKey = identityTokensKey(principal.identityProvider(), principal.externalIdentity());
            redisTemplate.opsForSet().add(identityKey, token);
            redisTemplate.expire(identityKey, tokenTtl);
        }
        return session;
    }

    @Override
    public AccessSession find(String accessToken) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(accessToken));
        if (values.isEmpty()) {
            return null;
        }
        String rolesValue = field(values, "roles");
        List<String> roles = rolesValue.isBlank()
                ? List.of()
                : List.of(rolesValue.split(ROLE_SEPARATOR, -1));
        String permissionsValue = optionalField(values, "permissions", "");
        List<String> permissions = permissionsValue.isBlank()
                ? List.of()
                : List.of(permissionsValue.split(ROLE_SEPARATOR, -1));
        IdentityPrincipal principal = new IdentityPrincipal(
                field(values, "userId"),
                field(values, "displayName"),
                field(values, "accountType"),
                roles,
                optionalField(values, "identityProvider", "MOCK"),
                optionalField(values, "externalIdentity", ""),
                permissions);
        AccessSession session = new AccessSession(
                accessToken,
                field(values, "tokenType"),
                OffsetDateTime.parse(field(values, "expiresAt")),
                principal);
        if (session.expiresAt().isBefore(OffsetDateTime.now(CHINA_ZONE_OFFSET))) {
            revoke(accessToken);
            return null;
        }
        return session;
    }

    @Override
    public void revoke(String accessToken) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(accessToken));
        Object userId = values.get("userId");
        Object provider = values.get("identityProvider");
        Object externalIdentity = values.get("externalIdentity");
        redisTemplate.delete(key(accessToken));
        if (userId != null) redisTemplate.opsForSet().remove(userTokensKey(userId.toString()), accessToken);
        if (provider != null && externalIdentity != null && !externalIdentity.toString().isBlank()) {
            redisTemplate.opsForSet().remove(
                    identityTokensKey(provider.toString(), externalIdentity.toString()), accessToken);
        }
    }

    @Override
    public void revokeAll(String userId) {
        String indexKey = userTokensKey(userId);
        var tokens = redisTemplate.opsForSet().members(indexKey);
        if (tokens != null && !tokens.isEmpty()) {
            tokens.forEach(this::revoke);
        }
        redisTemplate.delete(indexKey);
    }

    @Override
    public void revokeAllByExternalIdentity(String identityProvider, String externalIdentity) {
        if (identityProvider == null || externalIdentity == null || externalIdentity.isBlank()) return;
        String indexKey = identityTokensKey(identityProvider, externalIdentity);
        var tokens = redisTemplate.opsForSet().members(indexKey);
        if (tokens != null && !tokens.isEmpty()) tokens.forEach(this::revoke);
        redisTemplate.delete(indexKey);
    }

    private String key(String accessToken) {
        return KEY_PREFIX + accessToken;
    }

    private String userTokensKey(String userId) {
        return "bfh:auth:user-tokens:" + userId;
    }

    private String identityTokensKey(String provider, String externalIdentity) {
        return "bfh:auth:identity-tokens:" + provider + ":" + sha256(externalIdentity);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成登录身份索引", exception);
        }
    }

    private String field(Map<Object, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("Redis 登录会话缺少字段：" + name);
        }
        return value.toString();
    }

    private String optionalField(Map<Object, Object> values, String name, String defaultValue) {
        Object value = values.get(name);
        return value == null ? defaultValue : value.toString();
    }
}
