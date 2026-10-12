package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record VehicleReturnRequest(LocalDateTime returnedAt, BigDecimal endMileageKm, BigDecimal fuelLiter,
                                   String condition, List<String> photoUrls) {
}
