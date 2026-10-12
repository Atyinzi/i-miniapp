package com.iminiapp.backend.notification.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;

@Component
public class WechatAccessTokenClient {
    private final String appId;
    private final String appSecret;
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private volatile CachedToken cached;

    @Autowired
    public WechatAccessTokenClient(
            @Value("${app.wechat.app-id:}") String appId,
            @Value("${app.wechat.app-secret:}") String appSecret,
            ObjectMapper mapper,
            NotificationProperties properties) {
        this(appId, appSecret, WechatNotificationRestClientFactory.create(
                properties.getWechatConnectTimeout(), properties.getWechatReadTimeout()), mapper);
    }

    WechatAccessTokenClient(String appId, String appSecret, RestClient restClient, ObjectMapper mapper) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.restClient = restClient;
        this.mapper = mapper;
    }

    public boolean configured() {
        return StringUtils.hasText(appId) && StringUtils.hasText(appSecret);
    }

    public String token() {
        CachedToken value = cached;
        if (value != null && value.expiresAt().isAfter(Instant.now().plusSeconds(120))) return value.token();
        synchronized (this) {
            value = cached;
            if (value != null && value.expiresAt().isAfter(Instant.now().plusSeconds(120))) return value.token();
            URI uri = UriComponentsBuilder.fromUriString("https://api.weixin.qq.com/cgi-bin/token")
                    .queryParam("grant_type", "client_credential")
                    .queryParam("appid", appId)
                    .queryParam("secret", appSecret).build().encode().toUri();
            String body = restClient.get().uri(uri).retrieve().body(String.class);
            try {
                JsonNode root = mapper.readTree(body);
                String token = root.path("access_token").asText(null);
                long expiresIn = root.path("expires_in").asLong(0);
                if (!StringUtils.hasText(token) || expiresIn < 300) {
                    throw new IllegalStateException("微信访问令牌服务返回失败");
                }
                cached = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
                return token;
            } catch (RuntimeException ex) { throw ex; }
            catch (Exception ex) { throw new IllegalStateException("微信访问令牌响应无法解析", ex); }
        }
    }

    /**
     * Invalidates only the token that caused an authentication error. A concurrent
     * refresh therefore cannot be erased by an older in-flight request.
     */
    void invalidate(String rejectedToken) {
        if (!StringUtils.hasText(rejectedToken)) return;
        synchronized (this) {
            CachedToken value = cached;
            if (value != null && value.token().equals(rejectedToken)) cached = null;
        }
    }

    private record CachedToken(String token, Instant expiresAt) { }
}
