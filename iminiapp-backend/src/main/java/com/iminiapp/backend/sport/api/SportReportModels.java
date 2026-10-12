package com.iminiapp.backend.sport.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class SportReportModels {
    private SportReportModels() {}

    public record SportReportOverviewResponse(LocalDate startDate, LocalDate endDate, Long venueId,
            long bookingCount, long paidBookingCount, long completedBookingCount,
            long cancelledBookingCount, long refundedBookingCount, long grossRevenueCent,
            long refundAmountCent, long netRevenueCent, long verificationCount,
            BigDecimal verificationRatePercent, long memberBookingCount,
            long memberConsumptionCent, long capacitySlots, long occupiedSlots,
            BigDecimal utilizationRatePercent, LocalDateTime generatedAt) {}

    public record SportUtilizationResponse(String dimension, String dimensionKey, String dimensionName,
            long capacitySlots, long occupiedSlots, BigDecimal utilizationRatePercent) {}

    public record SportUsageDetailResponse(long bookingId, long orderId, String orderNo,
            long venueId, String venueName, long courtTypeId, String courtTypeName,
            long courtId, String courtName, LocalDate bookingDate, LocalTime startTime,
            LocalTime endTime, String bookingStatus, String orderStatus, long originalPriceCent,
            long paidAmountCent, long refundAmountCent, String memberLevelCode,
            String paymentChannel, LocalDateTime checkedInAt, LocalDateTime completedAt,
            String verificationOperator, String verificationDeviceNo) {}
}
