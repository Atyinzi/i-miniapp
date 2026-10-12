package com.iminiapp.backend.shared.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlMigrationSyntaxSafetyTests {
    private static final Pattern DEPRECATED_VALUES = Pattern.compile(
            "(?i)ON\\s+DUPLICATE\\s+KEY\\s+UPDATE[\\s\\S]*?VALUES\\s*\\(");
    private static final Pattern INTEGER_DISPLAY_WIDTH = Pattern.compile(
            "(?i)\\b(?:TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT)\\s*\\(\\d+\\)");

    @Test
    void newMigrationsDoNotReintroduceMysqlDeprecatedSyntax() throws IOException {
        Path directory = Path.of("src/main/resources/db/migration");
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(candidate -> migrationVersion(candidate) >= 65)
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String sql = Files.readString(path);
                assertFalse(DEPRECATED_VALUES.matcher(sql).find(),
                        () -> path.getFileName() + " uses deprecated VALUES(column) upsert syntax");
                assertFalse(INTEGER_DISPLAY_WIDTH.matcher(sql).find(),
                        () -> path.getFileName() + " uses a deprecated integer display width");
            }
        }
        assertTrue(Files.exists(directory.resolve("V65__normalize_deprecated_integer_display_widths.sql")));
    }

    private static int migrationVersion(Path path) {
        String name = path.getFileName().toString();
        if (!name.matches("V\\d+__.*\\.sql")) return -1;
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
