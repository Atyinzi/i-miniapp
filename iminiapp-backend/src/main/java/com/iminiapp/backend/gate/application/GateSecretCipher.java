package com.iminiapp.backend.gate.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** 闸机设备密钥使用独立于用户隐私数据的 AES-GCM 主密钥。 */
@Component
public class GateSecretCipher {
    private static final int NONCE_LENGTH = 12;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public GateSecretCipher(@Value("${app.gate.secret-encryption-key:local-development-gate-secret-key}") String source) {
        try {
            key = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化闸机密钥加密组件", ex);
        }
    }

    public byte[] encrypt(String value) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (Exception ex) {
            throw new IllegalStateException("闸机设备密钥加密失败", ex);
        }
    }

    public String decrypt(byte[] value) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(value);
            byte[] nonce = new byte[NONCE_LENGTH];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("闸机设备密钥解密失败", ex);
        }
    }
}
