package com.iminiapp.backend.vehicle.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record VehicleReportOverviewResponse(LocalDate startDate, LocalDate endDate,
        long internalApplications, long approvedApplications, long rejectedApplications,
        long dispatchCount, long completedTripCount, BigDecimal totalDistanceKm, BigDecimal totalFuelLiter,
        long socialRentalOrders, long rentalPaidAmountCent, long depositAmountCent,
        long depositDeductedCent, long depositRefundedCent, long supplementAmountCent) {
}
