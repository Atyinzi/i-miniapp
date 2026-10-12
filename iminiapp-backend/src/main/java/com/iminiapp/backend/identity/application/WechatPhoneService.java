package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.api.WechatPhoneResponse;
import com.iminiapp.backend.identity.infrastructure.WechatLoginProperties;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class WechatPhoneService {
    private static final Logger log = LoggerFactory.getLogger(WechatPhoneService.class);
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String PHONE_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    private final WechatLoginProperties properties;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WechatPhoneService(WechatLoginProperties properties) {
        this.properties = properties;
    }

    public WechatPhoneResponse exchange(String phoneCode) {
        if (!StringUtils.hasText(phoneCode)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "手机号授权 code 不能为空");
        }
        requireConfiguration();
        try {
            String accessToken = accessToken();
            URI uri = UriComponentsBuilder.fromUriString(PHONE_URL)
                    .queryParam("access_token", accessToken).build().encode().toUri();
            String requestBody = writeJson(Map.of("code", phoneCode.trim()));
            String response = restClient.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentLength(requestBody.getBytes(StandardCharsets.UTF_8).length)
                    .body(requestBody)
                    .retrieve().body(String.class);
            Map<?, ?> body = json(response);
            ensureSuccess(body, "微信手机号授权失败");
            Object value = body.get("phone_info");
            if (!(value instanceof Map<?, ?> phoneInfo)) {
                throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信手机号授权结果缺少 phone_info");
            }
            return new WechatPhoneResponse(text(phoneInfo.get("phoneNumber")),
                    text(phoneInfo.get("purePhoneNumber")), text(phoneInfo.get("countryCode")));
        } catch (BusinessException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            Map<?, ?> remote = tryJson(ex.getResponseBodyAsString());
            String remoteCode = text(remote.get("errcode"));
            String remoteMessage = text(remote.get("errmsg"));
            log.warn("Wechat phone API returned HTTP error: status={}, errcode={}, errmsg={}",
                    ex.getStatusCode().value(), remoteCode, remoteMessage);
            String detail = StringUtils.hasText(remoteCode)
                    ? "，微信错误码：" + remoteCode
                    : "，HTTP 状态码：" + ex.getStatusCode().value();
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信手机号接口请求失败" + detail);
        } catch (RestClientException ex) {
            // 不记录请求 URL，避免 access_token 出现在日志中。
            log.error("Wechat phone API network failure: exceptionType={}", ex.getClass().getName());
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信手机号服务暂时不可用");
        }
    }

    private String accessToken() {
        URI uri = UriComponentsBuilder.fromUriString(ACCESS_TOKEN_URL)
                .queryParam("grant_type", "client_credential")
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .build().encode().toUri();
        Map<?, ?> body = json(restClient.get().uri(uri).retrieve().body(String.class));
        ensureSuccess(body, "获取微信接口凭证失败");
        String token = text(body.get("access_token"));
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信接口凭证为空");
        }
        return token;
    }

    private Map<?, ?> json(String value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信接口返回结果无法解析");
        }
    }

    private Map<?, ?> tryJson(String value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信手机号请求参数无法序列化");
        }
    }

    private void ensureSuccess(Map<?, ?> body, String message) {
        Object value = body.get("errcode");
        long errorCode = value instanceof Number number ? number.longValue() : 0;
        if (errorCode != 0) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, message + "，错误码：" + errorCode);
        }
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(properties.getAppId()) || !StringUtils.hasText(properties.getAppSecret())) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "微信小程序登录尚未配置");
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
