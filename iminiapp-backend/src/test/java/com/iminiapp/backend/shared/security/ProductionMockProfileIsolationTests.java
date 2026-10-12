package com.iminiapp.backend.shared.security;

import com.iminiapp.backend.identity.application.MockLoginService;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.payment.infrastructure.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionMockProfileIsolationTests {

    @Test
    void mockServicesExistOnlyInExplicitLocalOrTestProfiles() {
        for (Class<?> type : ListOf.SERVICES) {
            Profile profile = type.getAnnotation(Profile.class);
            assertEquals(Set.of("local", "test"), Set.copyOf(Arrays.asList(profile.value())),
                    () -> type.getSimpleName() + " must not use a broad !prod profile");
        }
    }

    @Test
    void mockHttpControllersAreAbsentFromTheApplicationArtifact() {
        for (String className : ListOf.REMOVED_CONTROLLERS) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(className));
        }
    }

    private static final class ListOf {
        private static final Class<?>[] SERVICES = {
                MockLoginService.class,
                MockPaymentService.class,
                MockPaymentGateway.class
        };
        private static final String[] REMOVED_CONTROLLERS = {
                "com.iminiapp.backend.identity.api.MockAuthController",
                "com.iminiapp.backend.payment.api.MockPaymentController",
                "com.iminiapp.backend.refund.api.MockRefundController",
                "com.iminiapp.backend.refund.api.MockRefundApplicationCallbackController"
        };
    }
}
