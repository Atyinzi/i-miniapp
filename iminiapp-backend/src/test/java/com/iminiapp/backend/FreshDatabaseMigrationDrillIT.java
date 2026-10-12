package com.iminiapp.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in destructive migration drill. It creates and drops only a random database
 * whose name starts with bfh_migration_drill_. It never targets an application database.
 */
class FreshDatabaseMigrationDrillIT {
    private static final String SAFE_PREFIX = "bfh_migration_drill_";

    @Test
    void migratesAnEmptyMysqlDatabaseThroughTheLatestVersionAndCleansItUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("migrationDrill"),
                "Run explicitly with -DmigrationDrill=true");
        String serverUrl = System.getProperty("migrationDrill.serverUrl",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        String username = System.getProperty("migrationDrill.username", "root");
        String password = requiredSecret("MIGRATION_DRILL_MYSQL_PASSWORD");
        String database = SAFE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertTrue(database.matches("^bfh_migration_drill_[a-f0-9]{12}$"));

        try (Connection admin = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }

        String databaseUrl = serverUrl.replaceFirst("/\\?", "/" + database + "?");
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(databaseUrl, username, password)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load();
            var result = flyway.migrate();

            assertEquals("66", result.targetSchemaVersion.toString());
            try (Connection connection = DriverManager.getConnection(databaseUrl, username, password)) {
                assertEquals("66", scalarString(connection,
                        "SELECT version FROM flyway_schema_history WHERE success=1 "
                                + "ORDER BY installed_rank DESC LIMIT 1"));
                assertEquals(0, scalarInt(connection,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0"));
                assertEquals(2, columnCount(connection, database,
                        "bf_order_notification_outbox", "next_attempt_at", "dead_lettered_at"));
                assertTrue(scalarInt(connection,
                        "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='"
                                + database + "' AND trigger_name IN "
                                + "('trg_order_notification_created','trg_order_notification_status_changed')") == 2);
                assertTrue(scalarInt(connection,
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                                + database + "' AND table_name IN "
                                + "('bf_payment','bf_refund','bf_verification','bf_gate_device','bf_dining_reservation')") == 5);
            }
        } finally {
            if (!database.startsWith(SAFE_PREFIX)) {
                throw new IllegalStateException("Refusing to clean an unexpected database name");
            }
            try (Connection admin = DriverManager.getConnection(serverUrl, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    private static int columnCount(Connection connection, String schema, String table, String... columns)
            throws Exception {
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.length, "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=? AND table_name=? AND column_name IN (" + placeholders + ")")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            for (int index = 0; index < columns.length; index++) statement.setString(index + 3, columns[index]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static String requiredSecret(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
