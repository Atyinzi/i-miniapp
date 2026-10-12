package com.iminiapp.backend.sport.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class SportVerificationModels {
    private SportVerificationModels() {}

    public record SportVerificationRecordResponse(long id, long bookingId, long orderId, String orderNo,
            long venueId, String venueName, long courtId, String courtName, LocalDate bookingDate,
            LocalTime startTime, LocalTime endTime, long verificationId, long operatorId,
            String operatorName, String verificationChannel, String deviceNo,
            String bookingStatusBefore, String bookingStatusAfter, LocalDateTime verifiedAt) {}

    public record SportUsageResponse(long bookingId, long orderId, String orderNo,
            String orderStatus, String bookingStatus, LocalDateTime checkedInAt,
            LocalDateTime completedAt, Long verificationId) {}
}
