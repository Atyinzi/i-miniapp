package com.iminiapp.backend.vehicle.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyVehicleDemoMigrationTests {
    private static final String RESOURCE =
            "/db/migration/V59__deactivate_legacy_vehicle_demo_data.sql";

    @Test
    void migrationScrubsPlaintextAndNeverUnconditionallyDeletesFulfillmentReferences() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            assertTrue(stream != null, "migration resource must exist");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("name_cipher = UNHEX('00')"));
        assertTrue(sql.contains("mobile_cipher = UNHEX('00')"));
        assertTrue(sql.contains("license_no_cipher = UNHEX('00')"));
        assertTrue(sql.contains("status = 'INACTIVE'"));
        assertTrue(sql.contains("NOT EXISTS ("));
        assertTrue(sql.contains("FROM bf_vehicle_dispatch"));
        assertFalse(sql.contains("13800000001"));
        assertFalse(sql.contains("13800000002"));
        assertFalse(sql.contains("演示司机张师傅"));
        assertFalse(sql.contains("演示司机李师傅"));
    }
}
