package com.iminiapp.backend.payment.infrastructure;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatPayV3CryptoTests {

    @Test
    void rsaSha256SignatureCanBeVerifiedAndRejectsTampering() throws Exception {
        KeyPair pair = rsaKeyPair();
        String message = "POST\n/v3/pay/transactions/jsapi\n1700000000\nnonce\n{}\n";
        String signature = WechatPayV3Crypto.sign(pair.getPrivate(), message);

        assertTrue(WechatPayV3Crypto.verify(pair.getPublic(), message, signature));
        assertFalse(WechatPayV3Crypto.verify(pair.getPublic(), message + "tampered", signature));
    }

    @Test
    void aesGcmNotificationCanBeDecryptedWithAssociatedData() throws Exception {
        String key = "0123456789abcdef0123456789abcdef";
        String nonce = "0123456789ab";
        String associatedData = "transaction";
        String cleartext = "{\"out_trade_no\":\"WXBFH100\"}";
        String ciphertext = encrypt(key, nonce, associatedData, cleartext);

        assertEquals(cleartext,
                WechatPayV3Crypto.decryptNotification(key, nonce, associatedData, ciphertext));
    }

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static String encrypt(String key, String nonce, String associatedData, String cleartext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(cipher.doFinal(cleartext.getBytes(StandardCharsets.UTF_8)));
    }
}
