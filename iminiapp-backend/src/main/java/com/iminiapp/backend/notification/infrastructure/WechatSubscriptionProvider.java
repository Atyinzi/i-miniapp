package com.iminiapp.backend.notification.infrastructure;

import com.iminiapp.backend.notification.application.*;
import com.iminiapp.backend.notification.domain.NotificationChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.notification.wechat-enabled", havingValue = "true")
public class WechatSubscriptionProvider implements NotificationProvider {
    private final WechatAccessTokenClient tokenClient;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final NotificationProperties properties;

    @Autowired
    public WechatSubscriptionProvider(WechatAccessTokenClient tokenClient, ObjectMapper mapper,
                                      NotificationProperties properties) {
        this(tokenClient, mapper, properties, WechatNotificationRestClientFactory.create(
                properties.getWechatConnectTimeout(), properties.getWechatReadTimeout()));
    }

    WechatSubscriptionProvider(WechatAccessTokenClient tokenClient, ObjectMapper mapper,
                               NotificationProperties properties, RestClient restClient) {
        this.tokenClient = tokenClient;
        this.mapper = mapper;
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override public NotificationChannel channel() { return NotificationChannel.WECHAT_SUBSCRIPTION; }
    @Override public String providerCode() { return "WECHAT_SUBSCRIPTION"; }
    @Override public boolean configured() { return tokenClient.configured(); }

    @Override
    public NotificationProviderResult send(NotificationProviderRequest request) {
        return send(request, true);
    }

    private NotificationProviderResult send(NotificationProviderRequest request, boolean allowTokenRefresh) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("touser", request.recipient());
            body.put("template_id", request.templateCode());
            body.put("miniprogram_state", properties.getWechatMiniprogramState());
            ObjectNode data = body.putObject("data");
            JsonNode source = mapper.readTree(request.payloadJson());
            Map<String, String> mapping = properties.wechatFieldMapping(request.eventType());
            if (mapping.isEmpty()) {
                return NotificationProviderResult.rejected("TEMPLATE_FIELDS_NOT_CONFIGURED", "微信模板字段尚未配置");
            }
            for (Map.Entry<String, String> field : mapping.entrySet()) {
                String value = source.path(field.getKey()).asText("");
                data.putObject(field.getValue()).put("value", value);
            }
            String accessToken = tokenClient.token();
            String response = restClient.post()
                    .uri("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + accessToken)
                    .body(mapper.writeValueAsString(body)).retrieve().body(String.class);
            JsonNode result = mapper.readTree(response);
            int errorCode = result.path("errcode").asInt(-1);
            if (errorCode == 0) return NotificationProviderResult.sent(null);
            if (invalidAccessToken(errorCode)) {
                tokenClient.invalidate(accessToken);
                if (allowTokenRefresh) return send(request, false);
            }
            String message = "微信订阅消息返回错误码 " + errorCode;
            return retryable(errorCode)
                    ? NotificationProviderResult.retryable("WECHAT_" + errorCode, message)
                    : NotificationProviderResult.rejected("WECHAT_" + errorCode, message);
        } catch (Exception ex) {
            return NotificationProviderResult.retryable("WECHAT_NETWORK_ERROR", "微信订阅消息服务暂时不可用");
        }
    }

    private boolean retryable(int code) {
        return code == -1 || code == 40001 || code == 40014 || code == 42001 || code == 45009;
    }

    private boolean invalidAccessToken(int code) {
        return code == 40001 || code == 40014 || code == 42001;
    }
}
