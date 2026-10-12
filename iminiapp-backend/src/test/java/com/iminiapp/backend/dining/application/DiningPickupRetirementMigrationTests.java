package com.iminiapp.backend.dining.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiningPickupRetirementMigrationTests {
    private static final String RESOURCE =
            "/db/migration/V61__retire_dining_pickup_fulfillment.sql";

    @Test
    void migrationPreservesHistoryButRejectsPickupByDefault() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            assertTrue(stream != null, "migration resource must exist");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("legacy_pickup_record TINYINT(1) NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("SET legacy_pickup_record = 1"));
        assertTrue(sql.contains("chk_dining_pickup_legacy_only"));
        assertTrue(sql.contains("'$.pickupEnabled', FALSE"));
        assertFalse(sql.toUpperCase().contains("DELETE FROM BF_DINING_RESERVATION"));
    }
}
