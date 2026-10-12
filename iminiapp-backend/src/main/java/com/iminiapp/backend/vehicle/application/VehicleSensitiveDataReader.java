package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.shared.security.SensitiveDataCipher;

/**
 * Reads legacy vehicle-driver ciphertext without ever interpreting malformed bytes as plaintext.
 * Historical databases may contain raw UTF-8 values from an old demo migration.
 */
final class VehicleSensitiveDataReader {
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final int MINIMUM_NON_EMPTY_CIPHERTEXT_BYTES = GCM_NONCE_BYTES + GCM_TAG_BYTES + 1;

    private final SensitiveDataCipher cipher;

    VehicleSensitiveDataReader(SensitiveDataCipher cipher) {
        this.cipher = cipher;
    }

    String decryptOrNull(byte[] value) {
        if (value == null || value.length < MINIMUM_NON_EMPTY_CIPHERTEXT_BYTES) return null;
        try {
            return cipher.decrypt(value);
        } catch (RuntimeException ignored) {
            // Deliberately do not log the payload or the crypto exception: both may expose legacy PII.
            return null;
        }
    }

    String maskedName(byte[] value) {
        String clear = decryptOrNull(value);
        return clear == null || clear.isBlank() ? "****" : clear.substring(0, 1) + "**";
    }

    String maskedMobile(byte[] value) {
        String clear = decryptOrNull(value);
        return clear == null || clear.length() < 7
                ? "****"
                : clear.substring(0, 3) + "****" + clear.substring(clear.length() - 4);
    }
}
