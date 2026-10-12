package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleApplicationResponse(
        long id, String applicationNo, String status, String departure, String destination,
        int passengerCount, BigDecimal estimatedKm, String purpose, LocalDateTime startAt, LocalDateTime endAt,
        String reviewerComment, Long dispatchId, Long vehicleId, String vehicleNo, String plateNo,
        Long driverId, String driverNo, String dispatchStatus, LocalDateTime createdAt) {
}
