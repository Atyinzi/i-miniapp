package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleApplicationCreateRequest(
        LocalDateTime startAt, LocalDateTime endAt, String departure, String destination,
        Integer passengerCount, BigDecimal estimatedKm, String purpose) {
}
