package com.iminiapp.backend.shared.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionEndpointGuardConfigurationTests {

    @Test
    void blocksAllKnownMockAndTestEndpointShapes() {
        assertTrue(unsafe("/api/v1/app/auth/mock-login"));
        assertTrue(unsafe("/api/v1/app/orders/1/mock-pay"));
        assertTrue(unsafe("/api/v1/app/orders/1/mock-refund"));
        assertTrue(unsafe("/api/v1/app/vehicle-rentals/1/mock-supplement-pay"));
        assertTrue(unsafe("/api/v1/callbacks/refunds/mock"));
        assertTrue(unsafe("/api/v1/callbacks/payment/test-callback"));
    }

    @Test
    void allowsRealBusinessEndpoints() {
        assertFalse(unsafe("/api/v1/app/auth/wechat-login"));
        assertFalse(unsafe("/api/v1/app/orders/1/pay"));
        assertFalse(unsafe("/api/v1/callbacks/wechat-pay"));
        assertFalse(unsafe("/api/v1/admin/refund-applications/1/approve"));
    }

    private static boolean unsafe(String path) {
        return ProductionEndpointGuardConfiguration.ProductionEndpointGuard.isUnsafePath(path);
    }
}
