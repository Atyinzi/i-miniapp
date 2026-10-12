package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VehicleSettlementResponse(long id, long orderId, long dispatchId, String status,
                                        LocalDateTime plannedReturnAt, LocalDateTime actualReturnAt,
                                        BigDecimal actualDistanceKm, int overtimeMinutes, BigDecimal overDistanceKm,
                                        long overtimeAmountCent, long overDistanceAmountCent,
                                        long depositDeductionCent, long depositRefundCent,
                                        long supplementAmountCent, long supplementPaidCent) {
}
