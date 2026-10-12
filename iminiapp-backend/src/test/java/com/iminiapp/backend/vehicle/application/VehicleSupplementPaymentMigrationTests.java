package com.iminiapp.backend.vehicle.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSupplementPaymentMigrationTests {
    @Test
    void migrationEnforcesOneWechatPaymentPerVehicleSettlement() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V62__add_vehicle_supplement_wechat_payment.sql")) {
            assertNotNull(stream);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("vehicle_settlement_id BIGINT NULL"));
            assertTrue(sql.contains("UNIQUE KEY uk_payment_vehicle_settlement"));
            assertTrue(sql.contains("FOREIGN KEY (vehicle_settlement_id) REFERENCES bf_vehicle_settlement(id)"));
            assertFalse(sql.toUpperCase().contains("MOCK"));
        }
    }
}
