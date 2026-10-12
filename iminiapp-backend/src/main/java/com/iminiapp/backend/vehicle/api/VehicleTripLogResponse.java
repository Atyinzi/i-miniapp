package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleTripLogResponse(long id, long dispatchId, String eventType, LocalDateTime eventAt,
                                     BigDecimal longitude, BigDecimal latitude, BigDecimal mileageKm,
                                     BigDecimal fuelLiter, String detail) {
}
