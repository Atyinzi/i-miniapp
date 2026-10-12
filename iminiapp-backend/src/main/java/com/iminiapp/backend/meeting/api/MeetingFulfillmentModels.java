package com.iminiapp.backend.meeting.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class MeetingFulfillmentModels {
    private MeetingFulfillmentModels() {}
    public record RevokeRequest(String reason) {}
    public record VerificationLogResponse(long id, long bookingId, String bookingNo, long verificationId,
                                          String action, String operatorName, String verificationChannel,
                                          String deviceNo, String statusBefore, String statusAfter,
                                          String note, LocalDateTime createdAt) {}
    public record UsageRecordResponse(long bookingId, String bookingNo, String orderNo,
                                      long roomId, String roomName, String meetingSubject,
                                      String bookingType, int attendees, LocalDate bookingDate,
                                      LocalTime startTime, LocalTime endTime, String bookingStatus,
                                      String usageStatus, String checkedInBy, LocalDateTime usageStartedAt,
                                      LocalDateTime usageEndedAt, long verificationId) {}
}
