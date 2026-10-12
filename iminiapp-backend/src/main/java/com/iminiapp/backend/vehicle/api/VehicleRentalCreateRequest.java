package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleRentalCreateRequest(Long modelId, String rentalMode, LocalDateTime startAt,
                                         LocalDateTime endAt, String departure, String destination,
                                         BigDecimal departureLongitude, BigDecimal departureLatitude,
                                         BigDecimal destinationLongitude, BigDecimal destinationLatitude,
                                         String coordinateSystem,
                                         Integer passengerCount, BigDecimal estimatedKm, String remark) {
}
