package com.iminiapp.backend.vehicle.api;

public record DriverProfileResponse(boolean driver, Long driverId, String driverNo, String status) {
    public static DriverProfileResponse notBound() { return new DriverProfileResponse(false, null, null, null); }
}
