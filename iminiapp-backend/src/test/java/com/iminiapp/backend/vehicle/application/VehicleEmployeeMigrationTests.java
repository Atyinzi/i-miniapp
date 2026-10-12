package com.iminiapp.backend.vehicle.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleEmployeeMigrationTests {
    @Test
    void migrationCreatesEncryptedEmployeeIdentityAndCapabilityRoles() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V67__add_vehicle_employee_identity.sql")) {
            assertNotNull(stream);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE bf_department"));
            assertTrue(sql.contains("CREATE TABLE bf_employee"));
            assertTrue(sql.contains("name_cipher VARBINARY"));
            assertTrue(sql.contains("mobile_cipher VARBINARY"));
            assertTrue(sql.contains("UNIQUE KEY uk_employee_user"));
            assertTrue(sql.contains("'EMPLOYEE','内部员工'"));
            assertTrue(sql.contains("'DRIVER','车辆司机'"));
            assertFalse(sql.contains("13800138000"));
        }
    }
}
