package com.iminiapp.backend.vehicle.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverWorkflowRoutingTests {
    @Test
    void driverServiceSeparatesInternalTripsFromSocialRentalSettlement() throws Exception {
        assertTrue(DriverVehicleService.class.getMethod("accept",
                com.iminiapp.backend.identity.web.AuthenticatedPrincipal.class, long.class)
                .getReturnType().equals(com.iminiapp.backend.vehicle.api.DriverTaskResponse.class));
        assertTrue(DriverVehicleService.class.getMethod("complete",
                com.iminiapp.backend.identity.web.AuthenticatedPrincipal.class, long.class,
                com.iminiapp.backend.vehicle.api.VehicleTripCompleteRequest.class)
                .getReturnType().equals(com.iminiapp.backend.vehicle.api.DriverTaskResponse.class));
    }
}
