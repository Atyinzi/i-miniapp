package com.iminiapp.backend.notification.infrastructure;

import com.iminiapp.backend.notification.application.NotificationProviderRequest;
import com.iminiapp.backend.notification.application.NotificationProviderResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatNotificationProviderTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private WechatAccessTokenClient tokenClient;
    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokenClient = new WechatAccessTokenClient("wx-app", "app-secret", builder.build(), mapper);
        properties = new NotificationProperties();
        properties.setWechatFields(Map.of("ORDER_PAID", "orderNo:character_string1,status:phrase2"));
    }

    @Test
    void invalidTokenIsEvictedAndRetriedOnceDuringTheSameDispatch() {
        expectToken("expired-token");
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=expired-token"))
                .andRespond(withSuccess("{\"errcode\":40001}", MediaType.APPLICATION_JSON));
        expectToken("fresh-token");
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=fresh-token"))
                .andExpect(content().json("""
                        {"miniprogram_state":"formal","data":{
                          "character_string1":{"value":"ORD-1"},
                          "phrase2":{"value":"PAID"}
                        }}
                        """, false))
                .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));

        NotificationProviderResult result = provider().send(request());
        assertTrue(result.success());
        server.verify();
    }

    @Test
    void repeatedInvalidTokenStopsAfterOneRefreshAndRemainsRetryable() {
        expectToken("expired-token");
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=expired-token"))
                .andRespond(withSuccess("{\"errcode\":40001}", MediaType.APPLICATION_JSON));
        expectToken("still-invalid-token");
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=still-invalid-token"))
                .andRespond(withSuccess("{\"errcode\":40014}", MediaType.APPLICATION_JSON));

        NotificationProviderResult result = provider().send(request());

        assertTrue(result.retryable());
        assertEquals("WECHAT_40014", result.errorCode());
        server.verify();
    }

    @Test
    void configuredTrialStateIsSentAndInvalidStateIsRejected() {
        properties.setWechatMiniprogramState("trial");
        expectToken("trial-token");
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=trial-token"))
                .andExpect(content().json("{\"miniprogram_state\":\"trial\"}", false))
                .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));

        assertTrue(provider().send(request()).success());
        assertThrows(IllegalArgumentException.class,
                () -> properties.setWechatMiniprogramState("preview"));
        server.verify();
    }

    private void expectToken(String token) {
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/token"
                        + "?grant_type=client_credential&appid=wx-app&secret=app-secret"))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));
    }

    private WechatSubscriptionProvider provider() {
        return new WechatSubscriptionProvider(tokenClient, mapper, properties, builder.build());
    }

    private NotificationProviderRequest request() {
        return new NotificationProviderRequest(1L, 7L, "openid", "template-id", "ORDER_PAID", "ORD-1",
                "{\"orderNo\":\"ORD-1\",\"status\":\"PAID\"}");
    }
}
