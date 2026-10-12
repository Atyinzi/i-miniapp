package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleRentalStatusResponse(
        long orderId, String orderNo, String statusCode, LocalDateTime statusUpdatedAt,
        Long dispatchId, String dispatchStatus, Long driverId, String driverName,
        String driverPhone, String driverPhoneMasked, String vehiclePlate,
        BigDecimal currentLongitude, BigDecimal currentLatitude,
        String coordinateSystem, LocalDateTime locationUpdatedAt) {
}
