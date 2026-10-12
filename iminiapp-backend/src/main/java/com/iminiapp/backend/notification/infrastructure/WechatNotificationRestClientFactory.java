package com.iminiapp.backend.notification.infrastructure;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Creates fail-fast clients for WeChat notification APIs. */
final class WechatNotificationRestClientFactory {
    private WechatNotificationRestClientFactory() { }

    static RestClient create(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
