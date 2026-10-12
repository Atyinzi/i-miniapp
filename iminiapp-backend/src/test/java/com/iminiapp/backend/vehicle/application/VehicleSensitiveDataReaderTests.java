package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.shared.security.SensitiveDataCipher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VehicleSensitiveDataReaderTests {
    private final SensitiveDataCipher cipher = new SensitiveDataCipher(
            "vehicle-reader-test-key-that-is-not-production");
    private final VehicleSensitiveDataReader reader = new VehicleSensitiveDataReader(cipher);

    @Test
    void decryptsAndMasksValidAesGcmDriverData() {
        assertEquals("张师傅", reader.decryptOrNull(cipher.encrypt("张师傅")));
        assertEquals("张**", reader.maskedName(cipher.encrypt("张师傅")));
        assertEquals("138****0001", reader.maskedMobile(cipher.encrypt("13800000001")));
    }

    @Test
    void rejectsLegacyPlaintextWithoutReturningOrMaskingAnyPartOfIt() {
        byte[] legacyName = "演示司机张师傅".getBytes(StandardCharsets.UTF_8);
        byte[] legacyMobile = "13800000001".getBytes(StandardCharsets.UTF_8);

        assertNull(reader.decryptOrNull(legacyName));
        assertNull(reader.decryptOrNull(legacyMobile));
        assertEquals("****", reader.maskedName(legacyName));
        assertEquals("****", reader.maskedMobile(legacyMobile));
    }

    @Test
    void returnsSafePlaceholdersForTombstonesAndTamperedCiphertext() {
        byte[] tampered = cipher.encrypt("sensitive-value");
        tampered[tampered.length - 1] ^= 1;

        assertNull(reader.decryptOrNull(new byte[]{0}));
        assertNull(reader.decryptOrNull(tampered));
        assertEquals("****", reader.maskedName(new byte[]{0}));
        assertEquals("****", reader.maskedMobile(tampered));
    }
}
