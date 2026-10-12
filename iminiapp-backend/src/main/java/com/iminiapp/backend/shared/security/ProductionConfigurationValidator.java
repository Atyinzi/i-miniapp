package com.iminiapp.backend.shared.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses to start a production process with missing, development or trivially weak configuration.
 * Only property names are reported so that secrets can never be copied into logs.
 */
@Component
@Profile("prod")
public final class ProductionConfigurationValidator implements InitializingBean {
    private static final Pattern WECHAT_APP_ID = Pattern.compile("wx[0-9a-fA-F]{16}");
    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("local", "test", "legacy-mock-refund");
    private static final Set<String> KNOWN_WEAK_SECRETS = Set.of(
            "local-only-change-me",
            "local-development-person-data-key",
            "local-only-refund-callback",
            "change-me",
            "changeme",
            "password",
            "123456",
            "test-signing-key",
            "test-person-data-key"
    );

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(environment);
    }

    static void validate(Environment environment) {
        List<String> invalid = new ArrayList<>();
        validateProfiles(environment, invalid);

        requireJdbcUrl(environment, "spring.datasource.url", invalid);
        requireDedicatedDatabaseUser(environment, "spring.datasource.username", invalid);
        requireSecret(environment, "spring.datasource.password", 12, invalid);

        requireHost(environment, "spring.data.redis.host", invalid);
        requirePort(environment, "spring.data.redis.port", invalid);
        requireSecret(environment, "spring.data.redis.password", 16, invalid);
        requireExact(environment, "app.auth.token-store", "redis", invalid);

        requireSecret(environment, "app.person-data-key", 32, invalid);
        requireSecret(environment, "app.verification.signing-key", 32, invalid);
        requireSecret(environment, "app.hotel.checkin-signing-key", 32, invalid);
        requireSecret(environment, "app.gate.secret-encryption-key", 32, invalid);
        requireDistinct(environment, "app.person-data-key", "app.verification.signing-key", invalid);
        requireDistinct(environment, "app.verification.signing-key", "app.hotel.checkin-signing-key", invalid);
        requireDistinct(environment, "app.person-data-key", "app.hotel.checkin-signing-key", invalid);
        requireDistinct(environment, "app.hotel.checkin-signing-key", "app.gate.secret-encryption-key", invalid);
        requireDistinct(environment, "app.person-data-key", "app.gate.secret-encryption-key", invalid);
        requireDistinct(environment, "app.verification.signing-key", "app.gate.secret-encryption-key", invalid);

        String appId = value(environment, "app.wechat.app-id");
        if (!WECHAT_APP_ID.matcher(appId).matches()) invalid.add("app.wechat.app-id");
        requireSecret(environment, "app.wechat.app-secret", 32, invalid);
        requireExact(environment, "app.wechat.code2-session-url",
                "https://api.weixin.qq.com/sns/jscode2session", invalid);

        validateWechatPay(environment, invalid);
        validateNotificationDelivery(environment, invalid);

        if (!invalid.isEmpty()) {
            throw new IllegalStateException(
                    "Production configuration rejected. Missing or unsafe properties: "
                            + String.join(", ", invalid.stream().distinct().sorted().toList()));
        }
    }

    private static void validateWechatPay(Environment environment, List<String> invalid) {
        String enabledName = "app.wechat-pay-v3.enabled";
        boolean enabled = booleanSetting(environment, enabledName, invalid);
        if (!enabled) return;

        String paymentAppId = value(environment, "app.wechat-pay-v3.app-id");
        if (!WECHAT_APP_ID.matcher(paymentAppId).matches()
                || !paymentAppId.equals(value(environment, "app.wechat.app-id"))) {
            invalid.add("app.wechat-pay-v3.app-id");
        }
        requirePattern(environment, "app.wechat-pay-v3.merchant-id", "[0-9]{6,32}", invalid);
        requirePattern(environment, "app.wechat-pay-v3.merchant-serial-number", "[0-9A-Fa-f]{16,64}", invalid);
        requireSecret(environment, "app.wechat-pay-v3.api-v3-key", 32, invalid);
        if (value(environment, "app.wechat-pay-v3.api-v3-key")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32) {
            invalid.add("app.wechat-pay-v3.api-v3-key");
        }
        requireReadableFile(environment, "app.wechat-pay-v3.private-key-path", invalid);
        requireReadableFile(environment, "app.wechat-pay-v3.platform-public-key-path", invalid);
        requireHttpsUrl(environment, "app.wechat-pay-v3.payment-notify-url", false, invalid);
        requireHttpsUrl(environment, "app.wechat-pay-v3.refund-notify-url", false, invalid);
        requireHttpsUrl(environment, "app.wechat-pay-v3.base-url", true, invalid);
    }

    private static void validateNotificationDelivery(Environment environment, List<String> invalid) {
        boolean sending = booleanSetting(environment, "app.notification.sending-enabled", invalid);
        boolean worker = booleanSetting(environment, "app.notification.worker-enabled", invalid);
        boolean wechat = booleanSetting(environment, "app.notification.wechat-enabled", invalid);
        if (!(sending || worker || wechat)) return;
        if (!(sending && worker && wechat)) {
            invalid.add("app.notification sending/worker/wechat switches (must be enabled together)");
            return;
        }
        String state = value(environment, "app.notification.wechat-miniprogram-state");
        if (!Set.of("formal", "developer", "trial").contains(state)) {
            invalid.add("app.notification.wechat-miniprogram-state");
        }
        boolean configuredEvent = false;
        for (String event : List.of("ORDER_CREATED", "ORDER_PAID", "ORDER_COMPLETED", "ORDER_REFUNDED",
                "ORDER_WAITING_DRIVER", "ORDER_ACCEPTED")) {
            String template = value(environment, "app.notification.wechat-templates." + event);
            String fields = value(environment, "app.notification.wechat-fields." + event);
            if (StringUtils.hasText(template) != StringUtils.hasText(fields)) {
                invalid.add("app.notification.wechat template/fields for " + event);
            }
            configuredEvent |= StringUtils.hasText(template) && StringUtils.hasText(fields);
        }
        if (!configuredEvent) invalid.add("app.notification.wechat templates and field mappings");
    }

    private static boolean booleanSetting(Environment environment, String name, List<String> invalid) {
        String raw = value(environment, name);
        if (!StringUtils.hasText(raw)) return false;
        if (!Set.of("true", "false").contains(raw.toLowerCase(Locale.ROOT))) {
            invalid.add(name);
            return false;
        }
        return Boolean.parseBoolean(raw);
    }

    private static void requirePattern(
            Environment environment, String name, String pattern, List<String> invalid) {
        if (!value(environment, name).matches(pattern)) invalid.add(name);
    }

    private static void requireReadableFile(Environment environment, String name, List<String> invalid) {
        String candidate = value(environment, name);
        try {
            if (!StringUtils.hasText(candidate) || !Files.isRegularFile(Path.of(candidate))
                    || !Files.isReadable(Path.of(candidate))) invalid.add(name);
        } catch (RuntimeException exception) {
            invalid.add(name);
        }
    }

    private static void requireHttpsUrl(
            Environment environment, String name, boolean allowWechatHost, List<String> invalid) {
        try {
            URI uri = URI.create(value(environment, name));
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean unsafeHost = host.isBlank() || host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("0.0.0.0") || host.endsWith(".example.com") || host.equals("example.com");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || unsafeHost || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || (!allowWechatHost && host.equals("api.mch.weixin.qq.com"))) {
                invalid.add(name);
            }
        } catch (RuntimeException exception) {
            invalid.add(name);
        }
    }

    private static void validateProfiles(Environment environment, List<String> invalid) {
        Set<String> active = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        active.retainAll(NON_PRODUCTION_PROFILES);
        if (!active.isEmpty()) invalid.add("spring.profiles.active (prod cannot be combined with local/test/mock profiles)");
    }

    private static void requireJdbcUrl(Environment environment, String name, List<String> invalid) {
        String candidate = value(environment, name).toLowerCase(Locale.ROOT);
        if (!candidate.startsWith("jdbc:mysql://") || candidate.contains("example.com")
                || candidate.contains("iminiapp_test")) {
            invalid.add(name);
        }
    }

    private static void requireDedicatedDatabaseUser(Environment environment, String name, List<String> invalid) {
        String candidate = value(environment, name);
        if (!StringUtils.hasText(candidate)
                || Set.of("root", "admin", "administrator").contains(candidate.toLowerCase(Locale.ROOT))) {
            invalid.add(name);
        }
    }

    private static void requireHost(Environment environment, String name, List<String> invalid) {
        String candidate = value(environment, name).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(candidate) || candidate.contains("example.com")) invalid.add(name);
    }

    private static void requirePort(Environment environment, String name, List<String> invalid) {
        try {
            int port = Integer.parseInt(value(environment, name));
            if (port < 1 || port > 65535) invalid.add(name);
        } catch (NumberFormatException ex) {
            invalid.add(name);
        }
    }

    private static void requireSecret(Environment environment, String name, int minimumLength, List<String> invalid) {
        if (isWeakSecret(value(environment, name), minimumLength)) invalid.add(name);
    }

    private static boolean isWeakSecret(String candidate, int minimumLength) {
        if (!StringUtils.hasText(candidate) || candidate.length() < minimumLength) return true;
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (KNOWN_WEAK_SECRETS.contains(normalized)
                || normalized.contains("change-me")
                || normalized.startsWith("local-only-")
                || normalized.startsWith("test-")) {
            return true;
        }
        return candidate.chars().distinct().count() < 4;
    }

    private static void requireExact(
            Environment environment, String name, String expected, List<String> invalid) {
        if (!expected.equals(value(environment, name))) invalid.add(name);
    }

    private static void requireDistinct(
            Environment environment, String first, String second, List<String> invalid) {
        String firstValue = value(environment, first);
        String secondValue = value(environment, second);
        if (StringUtils.hasText(firstValue) && firstValue.equals(secondValue)) {
            invalid.add(first + " / " + second + " (must use different keys)");
        }
    }

    private static String value(Environment environment, String name) {
        String candidate = environment.getProperty(name);
        return candidate == null ? "" : candidate.trim();
    }
}
