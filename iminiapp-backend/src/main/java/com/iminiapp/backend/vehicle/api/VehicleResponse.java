package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;

public record VehicleResponse(long id, String vehicleNo, String plateNo, String modelCode,
                              String modelName, int seatCount, String status, BigDecimal mileageKm) {
}
