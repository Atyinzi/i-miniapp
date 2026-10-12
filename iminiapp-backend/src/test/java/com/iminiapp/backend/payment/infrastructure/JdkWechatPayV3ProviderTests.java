package com.iminiapp.backend.payment.infrastructure;

import com.iminiapp.backend.payment.application.WechatPayV3Provider;
import com.iminiapp.backend.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkWechatPayV3ProviderTests {
    private static final Instant NOW = Instant.parse("2026-08-12T06:00:00Z");

    private final Path tempDir = Path.of("target", "wechat-pay-v3-test-keys");

    @BeforeEach
    void createTestKeyDirectory() throws Exception {
        Files.createDirectories(tempDir);
    }

    @Test
    void disabledByDefaultAndPrepayFailsBeforeAnyNetworkRequest() {
        WechatPayV3Properties properties = new WechatPayV3Properties();
        JdkWechatPayV3Provider provider = new JdkWechatPayV3Provider(properties, new ObjectMapper());

        assertFalse(provider.availability().available());
        BusinessException exception = assertThrows(BusinessException.class, () -> provider.createJsapiPrepay(
                new WechatPayV3Provider.JsapiPrepayCommand("WX1", "order", 1, "CNY", "openid")));
        assertTrue(exception.getMessage().contains("disabled"));
    }

    @Test
    void enabledButIncompleteConfigurationIsExplicitlyUnavailable() {
        WechatPayV3Properties properties = new WechatPayV3Properties();
        properties.setEnabled(true);

        WechatPayV3Provider.Availability availability =
                new JdkWechatPayV3Provider(properties, new ObjectMapper()).availability();

        assertFalse(availability.available());
        assertTrue(availability.reason().contains("merchant-id"));
        assertTrue(availability.reason().contains("api-v3-key"));
    }

    @Test
    void signedCallbackIsVerifiedThenAesGcmResourceIsDecrypted() throws Exception {
        KeyPair merchant = WechatPayV3CryptoTests.rsaKeyPair();
        KeyPair platform = WechatPayV3CryptoTests.rsaKeyPair();
        Path privateKey = tempDir.resolve("merchant_private.pem");
        Path publicKey = tempDir.resolve("platform_public.pem");
        writePem(privateKey, "PRIVATE KEY", merchant.getPrivate().getEncoded());
        writePem(publicKey, "PUBLIC KEY", platform.getPublic().getEncoded());

        WechatPayV3Properties properties = configured(privateKey, publicKey);
        ObjectMapper mapper = new ObjectMapper();
        JdkWechatPayV3Provider provider = new JdkWechatPayV3Provider(
                properties, mapper, HttpClient.newHttpClient(), Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        String cleartext = "{\"mchid\":\"1900000109\",\"appid\":\"wx-test\","
                + "\"out_trade_no\":\"WXBFH100\",\"transaction_id\":\"4200001\","
                + "\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":100}}";
        String nonce = "0123456789ab";
        String associatedData = "transaction";
        String ciphertext = WechatPayV3CryptoTests.encrypt(
                properties.getApiV3Key(), nonce, associatedData, cleartext);
        String rawBody = mapper.writeValueAsString(mapper.readTree("""
                {"id":"notice-1","event_type":"TRANSACTION.SUCCESS","resource":{
                  "original_type":"transaction","algorithm":"AEAD_AES_256_GCM",
                  "ciphertext":"%s","associated_data":"%s","nonce":"%s"}}
                """.formatted(ciphertext, associatedData, nonce)));
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String callbackNonce = "callback-nonce";
        String signature = WechatPayV3Crypto.sign(platform.getPrivate(),
                timestamp + "\n" + callbackNonce + "\n" + rawBody + "\n");

        WechatPayV3Provider.VerifiedNotification notification = provider.verifyAndDecrypt(
                new WechatPayV3Provider.CallbackHeaders(
                        timestamp, callbackNonce, signature, "PUB_KEY_ID_1", "WECHATPAY2-SHA256-RSA2048"),
                rawBody);

        assertEquals("notice-1", notification.notificationId());
        assertEquals("TRANSACTION.SUCCESS", notification.eventType());
        assertEquals("WXBFH100", mapper.readTree(notification.decryptedResourceJson())
                .path("out_trade_no").asText());
    }

    @Test
    void callbackOutsideReplayWindowIsRejected() throws Exception {
        KeyPair merchant = WechatPayV3CryptoTests.rsaKeyPair();
        KeyPair platform = WechatPayV3CryptoTests.rsaKeyPair();
        Path privateKey = tempDir.resolve("merchant_private.pem");
        Path publicKey = tempDir.resolve("platform_public.pem");
        writePem(privateKey, "PRIVATE KEY", merchant.getPrivate().getEncoded());
        writePem(publicKey, "PUBLIC KEY", platform.getPublic().getEncoded());
        JdkWechatPayV3Provider provider = new JdkWechatPayV3Provider(
                configured(privateKey, publicKey), new ObjectMapper(), HttpClient.newHttpClient(),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

        assertThrows(BusinessException.class, () -> provider.verifyAndDecrypt(
                new WechatPayV3Provider.CallbackHeaders(
                        String.valueOf(NOW.minusSeconds(301).getEpochSecond()), "nonce", "invalid",
                        "PUB_KEY_ID_1", null), "{}"));
    }

    private static WechatPayV3Properties configured(Path privateKey, Path publicKey) {
        WechatPayV3Properties properties = new WechatPayV3Properties();
        properties.setEnabled(true);
        properties.setAppId("wx-test");
        properties.setMerchantId("1900000109");
        properties.setMerchantSerialNumber("MERCHANT_SERIAL_1");
        properties.setPrivateKeyPath(privateKey.toString());
        properties.setApiV3Key("0123456789abcdef0123456789abcdef");
        properties.setPlatformPublicKeyPath(publicKey.toString());
        properties.setPlatformKeyId("PUB_KEY_ID_1");
        properties.setPaymentNotifyUrl("https://api.example.test/api/v1/callbacks/wechat-pay/payment");
        properties.setRefundNotifyUrl("https://api.example.test/api/v1/callbacks/wechat-pay/refund");
        return properties;
    }

    private static void writePem(Path path, String label, byte[] encoded) throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        Files.writeString(path, "-----BEGIN " + label + "-----\n" + base64
                + "\n-----END " + label + "-----\n", StandardCharsets.US_ASCII);
    }
}
