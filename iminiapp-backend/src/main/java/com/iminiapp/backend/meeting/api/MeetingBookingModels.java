package com.iminiapp.backend.meeting.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 会议预订申请、配套服务和统一订单响应模型。 */
public final class MeetingBookingModels {
    private MeetingBookingModels() {}

    public record ServiceSelection(long serviceId, int quantity) {}

    public record CreateBookingRequest(long roomId, String bookingType, String meetingSubject,
                                       LocalDate bookingDate, LocalTime startTime, LocalTime endTime,
                                       int attendees, String contactName, String contactMobile,
                                       String remark, List<ServiceSelection> services) {}

    public record BookingServiceResponse(long serviceId, String serviceCode, String serviceName,
                                         String serviceType, String pricingUnit, int quantity,
                                         long unitPriceCent, long amountCent) {}

    public record PriceSnapshotResponse(long priceRuleId, String ruleCode, String ruleName,
                                        int priority, long venueAmountCent, long serviceAmountCent,
                                        long totalAmountCent, LocalDateTime quotedAt) {}

    public record BookingResponse(long id, String bookingNo, Long orderId, String orderNo,
                                  String bookingType, String meetingSubject, String status,
                                  long roomId, String roomCode, String roomName, String location,
                                  LocalDate bookingDate, LocalTime startTime, LocalTime endTime,
                                  int attendees, String contactName, String contactMobile, String remark,
                                  PriceSnapshotResponse price, List<BookingServiceResponse> services,
                                  LocalDateTime submittedAt, LocalDateTime occupiedAt,
                                  LocalDateTime releasedAt, LocalDateTime cancelledAt,
                                  LocalDateTime createdAt) {}
}
