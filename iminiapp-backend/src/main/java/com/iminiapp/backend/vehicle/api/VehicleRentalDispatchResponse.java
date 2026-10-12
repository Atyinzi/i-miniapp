package com.iminiapp.backend.vehicle.api;

import java.time.LocalDateTime;

public record VehicleRentalDispatchResponse(long dispatchId, long orderId, String orderNo, String orderStatus,
                                            long vehicleId, String vehicleNo, String plateNo,
                                            long driverId, String driverNo, String dispatchStatus,
                                            LocalDateTime acceptedAt, LocalDateTime departedAt, LocalDateTime completedAt) {
}
