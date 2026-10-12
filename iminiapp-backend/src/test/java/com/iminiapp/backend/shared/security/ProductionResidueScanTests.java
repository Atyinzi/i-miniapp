package com.iminiapp.backend.shared.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionResidueScanTests {
    @Test
    void productionControllersExposeNoMockOrTestRoutes() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            List<Path> controllers = files.filter(path -> path.toString().endsWith("Controller.java")).toList();
            for (Path controller : controllers) {
                String source = Files.readString(controller).toLowerCase(Locale.ROOT);
                assertFalse(source.matches("(?s).*@[a-z]*mapping\\([^)]*['\"][^'\"]*/(mock|test)([-_/][^'\"]*)?['\"].*"),
                        "non-production route remains in " + controller);
                assertFalse(source.contains("/api/v1/ops/"),
                        "legacy OPS route remains in " + controller);
            }
        }
    }

    @Test
    void productionConfigurationContainsNoBundledCredentialOrPlaceholderHost() throws Exception {
        String production = Files.readString(Path.of("src/main/resources/application-prod.properties"));
        String environmentExample = Files.readString(Path.of("config/application-env.example"));
        String combined = production + "\n" + environmentExample;

        assertFalse(combined.contains("example.com"));
        assertFalse(combined.contains("BEGIN PRIVATE KEY"));
        assertFalse(combined.matches("(?s).*(AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}).*"));
        assertFalse(production.contains("local-only-"));
        assertFalse(production.contains("mock-callback"));
        assertTrue(production.contains("app.hotel.checkin-signing-key=${HOTEL_CHECKIN_SIGNING_KEY:}"));
    }

    @Test
    void retainedTestDoublesAreStrictlyProfileGatedAndHaveNoHttpController() throws Exception {
        for (String relative : List.of(
                "identity/application/MockLoginService.java",
                "payment/application/MockPaymentService.java",
                "payment/infrastructure/MockPaymentGateway.java")) {
            String source = Files.readString(Path.of("src/main/java/com/iminiapp/backend").resolve(relative));
            assertTrue(source.contains("@Profile({\"local\", \"test\"})"), relative);
            assertFalse(source.contains("@RestController"), relative);
        }
        assertFalse(Files.exists(Path.of(
                "src/main/java/com/iminiapp/backend/refund/application/MockRefundService.java")));
    }
}
