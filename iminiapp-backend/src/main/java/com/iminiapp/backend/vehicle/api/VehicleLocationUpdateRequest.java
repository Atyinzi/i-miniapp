package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleLocationUpdateRequest(BigDecimal longitude, BigDecimal latitude,
                                           String coordinateSystem, BigDecimal mileageKm,
                                           LocalDateTime eventAt, String remark) {
}
