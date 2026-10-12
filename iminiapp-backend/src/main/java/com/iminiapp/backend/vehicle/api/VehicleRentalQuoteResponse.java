package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleRentalQuoteResponse(long modelId, String modelCode, String modelName, int seatCount,
                                         String rentalMode, LocalDateTime startAt, LocalDateTime endAt,
                                         int availableCount, long rentalAmountCent, long depositAmountCent,
                                         long payableAmountCent, BigDecimal estimatedKm) {
}
