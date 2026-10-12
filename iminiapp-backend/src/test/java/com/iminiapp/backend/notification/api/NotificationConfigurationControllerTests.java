package com.iminiapp.backend.notification.api;

import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationConfigurationControllerTests {
    @Test
    void onlyTemplatesWithValidFieldMappingAreExposed() {
        NotificationProperties properties = new NotificationProperties();
        properties.setWechatTemplates(Map.of(
                "ORDER_PAID", "paid-template",
                "ORDER_COMPLETED", "completed-template"));
        properties.setWechatFields(Map.of("ORDER_PAID", "orderNo:character_string1"));
        properties.setSendingEnabled(true);
        properties.setWorkerEnabled(true);
        properties.setWechatEnabled(true);

        var response = new NotificationConfigurationController(properties)
                .configuration(mock(HttpServletRequest.class)).data();

        assertTrue(response.wechatSubscriptionConfigured());
        assertEquals(1, response.wechatTemplates().size());
        assertEquals("ORDER_PAID", response.wechatTemplates().get(0).eventType());
        assertEquals("paid-template", response.wechatTemplates().get(0).templateId());
    }

    @Test
    void disabledDeliveryDoesNotAskUsersForSubscriptionConsent() {
        NotificationProperties properties = new NotificationProperties();
        properties.setWechatTemplates(Map.of("ORDER_PAID", "paid-template"));
        properties.setWechatFields(Map.of("ORDER_PAID", "orderNo:character_string1"));

        var response = new NotificationConfigurationController(properties)
                .configuration(mock(HttpServletRequest.class)).data();

        assertFalse(response.wechatSubscriptionConfigured());
        assertTrue(response.wechatTemplates().isEmpty());
    }

    @Test
    void everyDeliverySwitchMustBeEnabledBeforeConsentIsRequested() {
        for (int disabledSwitch = 0; disabledSwitch < 3; disabledSwitch++) {
            NotificationProperties properties = new NotificationProperties();
            properties.setWechatTemplates(Map.of("ORDER_PAID", "paid-template"));
            properties.setWechatFields(Map.of("ORDER_PAID", "orderNo:character_string1"));
            properties.setSendingEnabled(disabledSwitch != 0);
            properties.setWorkerEnabled(disabledSwitch != 1);
            properties.setWechatEnabled(disabledSwitch != 2);

            var response = new NotificationConfigurationController(properties)
                    .configuration(mock(HttpServletRequest.class)).data();

            assertFalse(response.wechatSubscriptionConfigured());
            assertTrue(response.wechatTemplates().isEmpty());
        }
    }
}
