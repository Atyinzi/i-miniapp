package com.iminiapp.backend.shared.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoCatalogRetirementMigrationTests {
    @Test
    void migrationDeactivatesEveryKnownSampleCatalogWithoutDeletingReferencedRows() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V64__deactivate_unconfirmed_demo_catalogs.sql"));
        String upper = sql.toUpperCase(java.util.Locale.ROOT);

        for (String code : new String[]{"NORTH_HOTEL", "SCENIC_WEAPON_CITY", "MALL\\_%",
                "NORTH_HOTEL_DINING", "HOUSHAN_DINING", "IMINIAPP_BATH", "IMINIAPP_SPORTS"}) {
            assertTrue(sql.contains(code), "missing retirement rule for " + code);
        }
        assertTrue(upper.contains("SET SKU.STATUS='INACTIVE'"));
        assertTrue(upper.contains("SET ROOM_TYPE.STATUS='INACTIVE'"));
        assertTrue(upper.contains("SET DISH.STATUS='INACTIVE'"));
        assertFalse(upper.contains("DELETE FROM"));
        assertFalse(upper.contains("DROP TABLE"));
    }
}
