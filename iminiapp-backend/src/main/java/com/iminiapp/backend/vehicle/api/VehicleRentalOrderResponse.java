package com.iminiapp.backend.vehicle.api;

import java.time.LocalDateTime;

public record VehicleRentalOrderResponse(long orderId, String orderNo, String status, long modelId,
                                         String modelName, String rentalMode, long rentalAmountCent,
                                         long depositAmountCent, long payableAmountCent,
                                         LocalDateTime expiresAt, LocalDateTime createdAt) {
}
