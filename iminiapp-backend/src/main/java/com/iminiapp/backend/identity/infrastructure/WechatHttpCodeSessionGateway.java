package com.iminiapp.backend.identity.infrastructure;

import com.iminiapp.backend.identity.application.WechatCodeSessionGateway;
import com.iminiapp.backend.identity.domain.WechatCodeSession;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class WechatHttpCodeSessionGateway implements WechatCodeSessionGateway {

    private final WechatLoginProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public WechatHttpCodeSessionGateway(WechatLoginProperties properties) {
        this(properties, RestClient.create(), new ObjectMapper());
    }

    WechatHttpCodeSessionGateway(WechatLoginProperties properties, RestClient restClient) {
        this(properties, restClient, new ObjectMapper());
    }

    WechatHttpCodeSessionGateway(WechatLoginProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public WechatCodeSession exchange(String loginCode) {
        requireConfiguration();
        URI uri = UriComponentsBuilder.fromUriString(properties.getCode2SessionUrl())
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .queryParam("js_code", loginCode)
                .queryParam("grant_type", "authorization_code")
                .build()
                .encode()
                .toUri();
        try {
            // 微信成功和失败响应的 Content-Type 可能是 text/plain，不能直接按 Map 转换。
            String body = restClient.get().uri(uri).retrieve().body(String.class);
            return parse(body);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信登录服务暂时不可用");
        }
    }

    private WechatCodeSession parse(String responseBody) {
        Map<?, ?> body;
        try {
            body = objectMapper.readValue(responseBody, Map.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信登录返回结果无法解析");
        }
        if (body == null) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信登录服务返回空结果");
        }
        long errorCode = number(body.get("errcode"));
        if (errorCode != 0) {
            if (errorCode == 40029 || errorCode == 40163) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "微信登录凭证无效或已使用");
            }
            if (errorCode == 45011) {
                throw new BusinessException(CommonErrorCode.RATE_LIMITED, "微信登录请求过于频繁");
            }
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "微信登录验证失败，错误码：" + errorCode);
        }

        String openId = text(body.get("openid"));
        String sessionKey = text(body.get("session_key"));
        if (!StringUtils.hasText(openId) || !StringUtils.hasText(sessionKey)) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信登录结果缺少必要字段");
        }
        return new WechatCodeSession(openId, text(body.get("unionid")), sessionKey);
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(properties.getAppId()) || !StringUtils.hasText(properties.getAppSecret())) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信小程序登录尚未配置");
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }
}
