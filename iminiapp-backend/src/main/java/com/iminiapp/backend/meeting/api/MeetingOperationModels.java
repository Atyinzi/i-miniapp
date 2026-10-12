package com.iminiapp.backend.meeting.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class MeetingOperationModels {
    private MeetingOperationModels() {}

    public record BookingLedgerResponse(long id, String bookingNo, String orderNo, String bookingType,
                                        String meetingSubject, String status, long roomId, String roomCode,
                                        String roomName, LocalDate bookingDate, LocalTime startTime,
                                        LocalTime endTime, int attendees, String contactName,
                                        String contactMobile, long venueAmountCent, long serviceAmountCent,
                                        long totalAmountCent, long paidAmountCent, String approvalStatus,
                                        Integer approvalCurrentStep, String usageStatus,
                                        LocalDateTime checkedInAt, LocalDateTime completedAt,
                                        LocalDateTime createdAt) {}

    public record OverviewResponse(LocalDate startDate, LocalDate endDate, long bookingCount,
                                   long pendingCount, long approvedCount, long verifiedCount,
                                   long completedCount, long cancelledCount, long rejectedCount,
                                   long quotedAmountCent, long paidAmountCent, long serviceAmountCent,
                                   long attendeeCount, long verificationCount, long capacityMinutes,
                                   long occupiedMinutes, double utilizationRate) {}

    public record RoomUtilizationResponse(long roomId, String roomCode, String roomName,
                                          long bookingCount, long capacityMinutes,
                                          long occupiedMinutes, double utilizationRate) {}

    public record ServiceSalesResponse(long serviceId, String serviceCode, String serviceName,
                                       String serviceType, long bookingCount, long quantity,
                                       long salesAmountCent) {}
}
