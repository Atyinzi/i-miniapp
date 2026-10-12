package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DriverTaskResponse(long dispatchId, long orderId, String orderNo, String businessMode, String orderStatus,
                                 String dispatchStatus, long vehicleId, String vehicleNo, String plateNo,
                                 String departure, BigDecimal departureLongitude, BigDecimal departureLatitude,
                                 String destination, BigDecimal destinationLongitude, BigDecimal destinationLatitude,
                                 String coordinateSystem, LocalDateTime startAt, LocalDateTime endAt,
                                 Integer passengerCount, LocalDateTime acceptedAt, LocalDateTime departedAt,
                                 LocalDateTime completedAt) {
}
