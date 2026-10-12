package com.iminiapp.backend.payment.infrastructure;

import com.iminiapp.backend.payment.application.WechatPayV3Provider;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Component
public class JdkWechatPayV3Provider implements WechatPayV3Provider {
    private static final String AUTH_SCHEMA = "WECHATPAY2-SHA256-RSA2048";
    private final WechatPayV3Properties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public JdkWechatPayV3Provider(WechatPayV3Properties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper,
                HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build(),
                Clock.systemUTC(), new SecureRandom());
    }

    JdkWechatPayV3Provider(
            WechatPayV3Properties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock,
            SecureRandom secureRandom) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    @Override
    public Availability availability() {
        if (!properties.isEnabled()) return new Availability(false, "WeChat Pay v3 network client is disabled");
        List<String> errors = properties.configurationErrors();
        return errors.isEmpty()
                ? new Availability(true, "available")
                : new Availability(false, "Invalid WeChat Pay v3 configuration: " + String.join(", ", errors));
    }

    @Override
    public JsapiPrepayResult createJsapiPrepay(JsapiPrepayCommand command) {
        requireAvailable(CommonErrorCode.PAYMENT_CONFLICT);
        validatePrepay(command);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("appid", properties.getAppId());
        body.put("mchid", properties.getMerchantId());
        body.put("description", command.description());
        body.put("out_trade_no", command.outTradeNo());
        body.put("notify_url", properties.getPaymentNotifyUrl());
        ObjectNode amount = body.putObject("amount");
        amount.put("total", command.amountCent());
        amount.put("currency", normalizedCurrency(command.currency()));
        body.putObject("payer").put("openid", command.payerOpenId());

        JsonNode response = post("/v3/pay/transactions/jsapi", body, CommonErrorCode.PAYMENT_CONFLICT);
        String prepayId = requiredText(response, "prepay_id", CommonErrorCode.PAYMENT_CONFLICT);
        return buildJsapiInvocation(command.outTradeNo(), prepayId);
    }

    @Override
    public JsapiPrepayResult buildJsapiInvocation(String outTradeNo, String prepayId) {
        requireAvailable(CommonErrorCode.PAYMENT_CONFLICT);
        if (!StringUtils.hasText(outTradeNo) || !StringUtils.hasText(prepayId)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    "Invalid JSAPI invocation parameters");
        }
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String nonce = nonce();
        String packageValue = "prepay_id=" + prepayId;
        String paySign = WechatPayV3Crypto.sign(privateKey(),
                properties.getAppId() + "\n" + timestamp + "\n" + nonce + "\n" + packageValue + "\n");
        return new JsapiPrepayResult(outTradeNo, timestamp, nonce, packageValue, "RSA", paySign);
    }

    @Override
    public RefundResult requestRefund(RefundCommand command) {
        requireAvailable(CommonErrorCode.REFUND_CONFLICT);
        validateRefund(command);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("out_refund_no", command.outRefundNo());
        if (StringUtils.hasText(command.transactionId())) body.put("transaction_id", command.transactionId());
        else body.put("out_trade_no", command.outTradeNo());
        if (StringUtils.hasText(command.reason())) body.put("reason", command.reason());
        body.put("notify_url", properties.getRefundNotifyUrl());
        ObjectNode amount = body.putObject("amount");
        amount.put("refund", command.refundAmountCent());
        amount.put("total", command.totalAmountCent());
        amount.put("currency", normalizedCurrency(command.currency()));

        JsonNode response = post("/v3/refund/domestic/refunds", body, CommonErrorCode.REFUND_CONFLICT);
        return new RefundResult(
                requiredText(response, "refund_id", CommonErrorCode.REFUND_CONFLICT),
                requiredText(response, "status", CommonErrorCode.REFUND_CONFLICT));
    }

    @Override
    public VerifiedNotification verifyAndDecrypt(CallbackHeaders headers, String rawBody) {
        requireAvailable(CommonErrorCode.UNAUTHORIZED);
        if (headers == null || !StringUtils.hasText(rawBody)
                || !StringUtils.hasText(headers.timestamp())
                || !StringUtils.hasText(headers.nonce())
                || !StringUtils.hasText(headers.signature())
                || !StringUtils.hasText(headers.serial())) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Incomplete WeChat Pay callback signature headers");
        }
        if (headers.signature().startsWith("WECHATPAY/SIGNTEST/")) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "WeChat Pay signature test notification rejected");
        }
        if (StringUtils.hasText(headers.signatureType())
                && !AUTH_SCHEMA.equals(headers.signatureType())) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED,
                    "Unsupported WeChat Pay callback signature type");
        }
        if (!properties.getPlatformKeyId().equals(headers.serial())) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Unknown WeChat Pay platform key id");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(headers.timestamp());
        } catch (NumberFormatException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Invalid WeChat Pay callback timestamp");
        }
        long age = Math.abs(clock.instant().getEpochSecond() - timestamp);
        if (age > properties.getCallbackTolerance().toSeconds()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Expired WeChat Pay callback");
        }
        String message = headers.timestamp() + "\n" + headers.nonce() + "\n" + rawBody + "\n";
        if (!WechatPayV3Crypto.verify(platformPublicKey(), message, headers.signature())) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Invalid WeChat Pay callback signature");
        }
        try {
            JsonNode notification = objectMapper.readTree(rawBody);
            String id = requiredText(notification, "id", CommonErrorCode.INVALID_REQUEST);
            String eventType = requiredText(notification, "event_type", CommonErrorCode.INVALID_REQUEST);
            JsonNode resource = notification.path("resource");
            String resourceType = requiredText(resource, "original_type", CommonErrorCode.INVALID_REQUEST);
            if (!"AEAD_AES_256_GCM".equals(requiredText(
                    resource, "algorithm", CommonErrorCode.INVALID_REQUEST))) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                        "Unsupported WeChat Pay notification encryption algorithm");
            }
            String decrypted = WechatPayV3Crypto.decryptNotification(
                    properties.getApiV3Key(),
                    requiredText(resource, "nonce", CommonErrorCode.INVALID_REQUEST),
                    resource.path("associated_data").isMissingNode() || resource.path("associated_data").isNull()
                            ? null : resource.path("associated_data").asText(),
                    requiredText(resource, "ciphertext", CommonErrorCode.INVALID_REQUEST));
            JsonNode decryptedResource = objectMapper.readTree(decrypted);
            validateNotificationMerchant(resourceType, decryptedResource);
            return new VerifiedNotification(id, eventType, resourceType, decrypted);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Invalid WeChat Pay callback body");
        }
    }

    private void validateNotificationMerchant(String resourceType, JsonNode resource) {
        String merchantId = resource.path("mchid").asText(null);
        if (StringUtils.hasText(merchantId) && !properties.getMerchantId().equals(merchantId)) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED,
                    "WeChat Pay callback merchant does not match local configuration");
        }
        if ("transaction".equalsIgnoreCase(resourceType)) {
            String appId = resource.path("appid").asText(null);
            if (!properties.getAppId().equals(appId)) {
                throw new BusinessException(CommonErrorCode.UNAUTHORIZED,
                        "WeChat Pay callback appid does not match local configuration");
            }
        }
    }

    private JsonNode post(String path, JsonNode body, CommonErrorCode errorCode) {
        try {
            String rawBody = objectMapper.writeValueAsString(body);
            URI uri = URI.create(properties.getBaseUrl() + path);
            long timestamp = clock.instant().getEpochSecond();
            String nonce = nonce();
            String signatureMessage = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + rawBody + "\n";
            String signature = WechatPayV3Crypto.sign(privateKey(), signatureMessage);
            String authorization = AUTH_SCHEMA
                    + " mchid=\"" + properties.getMerchantId()
                    + "\",nonce_str=\"" + nonce
                    + "\",timestamp=\"" + timestamp
                    + "\",serial_no=\"" + properties.getMerchantSerialNumber()
                    + "\",signature=\"" + signature + "\"";
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.getRequestTimeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            verifyHttpResponse(response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String requestId = response.headers().firstValue("Request-ID").orElse("unknown");
                throw new BusinessException(errorCode,
                        "WeChat Pay request failed (HTTP " + response.statusCode() + ", request-id " + requestId + ")");
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(errorCode, "WeChat Pay request was interrupted");
        } catch (Exception exception) {
            throw new BusinessException(errorCode, "WeChat Pay request failed before a verified response was received");
        }
    }

    private void verifyHttpResponse(HttpResponse<String> response) {
        String timestamp = header(response, "Wechatpay-Timestamp");
        String nonce = header(response, "Wechatpay-Nonce");
        String signature = header(response, "Wechatpay-Signature");
        String serial = header(response, "Wechatpay-Serial");
        if (!properties.getPlatformKeyId().equals(serial)) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Unknown WeChat Pay response key id");
        }
        String message = timestamp + "\n" + nonce + "\n" + response.body() + "\n";
        if (!WechatPayV3Crypto.verify(platformPublicKey(), message, signature)) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Invalid WeChat Pay response signature");
        }
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED,
                        "Missing WeChat Pay response header " + name));
    }

    private void requireAvailable(CommonErrorCode errorCode) {
        Availability state = availability();
        if (!state.available()) throw new BusinessException(errorCode, state.reason());
    }

    private void validatePrepay(JsapiPrepayCommand command) {
        if (command == null || !StringUtils.hasText(command.outTradeNo())
                || command.outTradeNo().length() > 32
                || !StringUtils.hasText(command.description())
                || !StringUtils.hasText(command.payerOpenId())
                || command.amountCent() <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Invalid JSAPI prepay parameters");
        }
    }

    private void validateRefund(RefundCommand command) {
        boolean hasSource = command != null && (StringUtils.hasText(command.transactionId())
                || StringUtils.hasText(command.outTradeNo()));
        if (command == null || !StringUtils.hasText(command.outRefundNo())
                || command.outRefundNo().length() > 64 || !hasSource
                || command.refundAmountCent() <= 0 || command.totalAmountCent() <= 0
                || command.refundAmountCent() > command.totalAmountCent()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Invalid WeChat Pay refund parameters");
        }
    }

    private static String normalizedCurrency(String currency) {
        return StringUtils.hasText(currency) ? currency.trim().toUpperCase(java.util.Locale.ROOT) : "CNY";
    }

    private static String requiredText(JsonNode node, String field, CommonErrorCode code) {
        String value = node.path(field).asText(null);
        if (!StringUtils.hasText(value)) throw new BusinessException(code, "Missing WeChat Pay field: " + field);
        return value;
    }

    private PrivateKey privateKey() {
        try {
            return WechatPayV3Crypto.readPrivateKey(Path.of(properties.getPrivateKeyPath()));
        } catch (RuntimeException exception) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "WeChat Pay merchant private key is unavailable");
        }
    }

    private PublicKey platformPublicKey() {
        try {
            return WechatPayV3Crypto.readPublicKey(Path.of(properties.getPlatformPublicKeyPath()));
        } catch (RuntimeException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "WeChat Pay platform public key is unavailable");
        }
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
