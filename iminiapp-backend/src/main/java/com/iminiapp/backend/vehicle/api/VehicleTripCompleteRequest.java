package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;

public record VehicleTripCompleteRequest(BigDecimal endMileageKm, BigDecimal fuelLiter, String remark) {
}
