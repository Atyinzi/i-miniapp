package com.iminiapp.backend.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionConfigurationValidatorTests {

    @Test
    void acceptsCompleteProductionConfiguration() {
        MockEnvironment environment = validEnvironment();

        assertDoesNotThrow(() -> ProductionConfigurationValidator.validate(environment));
    }

    @Test
    void rejectsMissingAndKnownDevelopmentDefaultsWithoutLoggingValues() {
        MockEnvironment environment = validEnvironment()
                .withProperty("spring.datasource.username", "root")
                .withProperty("spring.datasource.password", "")
                .withProperty("spring.data.redis.password", "123456")
                .withProperty("app.person-data-key", "local-development-person-data-key")
                .withProperty("app.verification.signing-key", "local-only-change-me")
                .withProperty("app.wechat.app-id", "")
                .withProperty("app.wechat.app-secret", "");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationValidator.validate(environment));

        assertTrue(error.getMessage().contains("spring.datasource.username"));
        assertTrue(error.getMessage().contains("spring.data.redis.password"));
        assertTrue(error.getMessage().contains("app.person-data-key"));
        assertTrue(error.getMessage().contains("app.verification.signing-key"));
        assertTrue(error.getMessage().contains("app.wechat.app-id"));
        assertTrue(!error.getMessage().contains("local-development-person-data-key"));
    }

    @Test
    void rejectsProductionCombinedWithDevelopmentProfile() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles("prod", "local");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationValidator.validate(environment));

        assertTrue(error.getMessage().contains("spring.profiles.active"));
    }

    @Test
    void productionPropertiesExternalizeSecretsAndDisableExternalDeliveryByDefault() throws IOException {
        Properties properties = new Properties();
        try (var stream = getClass().getResourceAsStream("/application-prod.properties")) {
            assertTrue(stream != null, "production properties must exist");
            properties.load(stream);
        }

        assertEquals("${DB_PASSWORD:}", properties.getProperty("spring.datasource.password"));
        assertEquals("${REDIS_PASSWORD:}", properties.getProperty("spring.data.redis.password"));
        assertEquals("${PERSON_DATA_KEY:}", properties.getProperty("app.person-data-key"));
        assertEquals("${VERIFICATION_SIGNING_KEY:}", properties.getProperty("app.verification.signing-key"));
        assertEquals("${HOTEL_CHECKIN_SIGNING_KEY:}", properties.getProperty("app.hotel.checkin-signing-key"));
        assertEquals("${GATE_SECRET_ENCRYPTION_KEY:}", properties.getProperty("app.gate.secret-encryption-key"));
        assertEquals("${WECHAT_APP_SECRET:}", properties.getProperty("app.wechat.app-secret"));
        assertEquals("${WECHAT_PAY_V3_ENABLED:false}", properties.getProperty("app.wechat-pay-v3.enabled"));
        assertEquals("${NOTIFICATION_SENDING_ENABLED:false}", properties.getProperty("app.notification.sending-enabled"));
        assertEquals("${NOTIFICATION_WORKER_ENABLED:false}", properties.getProperty("app.notification.worker-enabled"));
    }

    @Test
    void rejectsEnablingWechatPayWithoutCompleteChannelConfiguration() {
        MockEnvironment environment = validEnvironment()
                .withProperty("app.wechat-pay-v3.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationValidator.validate(environment));

        assertTrue(error.getMessage().contains("app.wechat-pay-v3.merchant-id"));
        assertTrue(error.getMessage().contains("app.wechat-pay-v3.private-key-path"));
        assertTrue(error.getMessage().contains("app.wechat-pay-v3.payment-notify-url"));
    }

    @Test
    void rejectsPartiallyEnabledNotificationDelivery() {
        MockEnvironment environment = validEnvironment()
                .withProperty("app.notification.sending-enabled", "true")
                .withProperty("app.notification.worker-enabled", "false")
                .withProperty("app.notification.wechat-enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationValidator.validate(environment));

        assertTrue(error.getMessage().contains("must be enabled together"));
    }

    private static MockEnvironment validEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/iminiapp")
                .withProperty("spring.datasource.username", "iminiapp_app")
                .withProperty("spring.datasource.password", "Db#2026-production-password")
                .withProperty("spring.data.redis.host", "redis.internal")
                .withProperty("spring.data.redis.port", "6379")
                .withProperty("spring.data.redis.password", "Redis#2026-production-password")
                .withProperty("app.auth.token-store", "redis")
                .withProperty("app.person-data-key", "person-data-key-2026-8f9d1c7e6a5b4c3d")
                .withProperty("app.verification.signing-key", "verification-key-2026-3d4c5b6a7e8f9a1b")
                .withProperty("app.hotel.checkin-signing-key", "hotel-checkin-key-2026-9a8b7c6d5e4f3210")
                .withProperty("app.gate.secret-encryption-key", "gate-secret-key-2026-a1b2c3d4e5f60789")
                .withProperty("app.wechat.app-id", "wx1234567890abcdef")
                .withProperty("app.wechat.app-secret", "0123456789abcdef0123456789abcdef")
                .withProperty("app.wechat.code2-session-url", "https://api.weixin.qq.com/sns/jscode2session");
        environment.setActiveProfiles("prod");
        return environment;
    }
}
