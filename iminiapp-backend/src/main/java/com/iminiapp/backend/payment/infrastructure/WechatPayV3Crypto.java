package com.iminiapp.backend.payment.infrastructure;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class WechatPayV3Crypto {
    private WechatPayV3Crypto() {
    }

    public static PrivateKey readPrivateKey(Path path) {
        try {
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            byte[] encoded = decodePem(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to read WeChat Pay merchant private key", exception);
        }
    }

    public static PublicKey readPublicKey(Path path) {
        try {
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            if (pem.contains("BEGIN CERTIFICATE")) {
                byte[] certificate = decodePem(pem, "CERTIFICATE");
                X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(certificate));
                return x509.getPublicKey();
            }
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to read WeChat Pay platform public key", exception);
        }
    }

    public static String sign(PrivateKey privateKey, String message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign WeChat Pay request", exception);
        }
    }

    public static boolean verify(PublicKey publicKey, String message, String signatureValue) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureValue));
        } catch (IllegalArgumentException exception) {
            return false;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify WeChat Pay signature", exception);
        }
    }

    public static String decryptNotification(
            String apiV3Key,
            String nonce,
            String associatedData,
            String ciphertext) {
        try {
            byte[] key = apiV3Key.getBytes(StandardCharsets.UTF_8);
            if (key.length != 32) throw new IllegalArgumentException("API v3 key must be exactly 32 UTF-8 bytes");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to decrypt WeChat Pay notification", exception);
        }
    }

    private static byte[] decodePem(String pem, String label) {
        String body = pem.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        if (body.isEmpty()) throw new IllegalArgumentException("Invalid PEM: " + label);
        return Base64.getDecoder().decode(body);
    }
}
