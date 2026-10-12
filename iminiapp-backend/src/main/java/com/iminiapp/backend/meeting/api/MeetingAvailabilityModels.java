package com.iminiapp.backend.meeting.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 用户端会议室可订日历与动态报价模型。 */
public final class MeetingAvailabilityModels {
    private MeetingAvailabilityModels() {}

    public record PriceSnapshot(long priceRuleId, String ruleCode, String ruleName,
                                int priority, long venuePriceCent) {}

    public record AvailableSlotResponse(long openingRuleId, LocalTime startTime, LocalTime endTime,
                                        int slotMinutes, boolean available, String unavailableCode,
                                        String unavailableReason, PriceSnapshot price) {}

    public record RequestedQuoteResponse(LocalTime startTime, LocalTime endTime, long durationMinutes,
                                         boolean available, String unavailableCode, String unavailableReason,
                                         long venuePriceCent, PriceSnapshot price) {}

    public record AvailabilityResponse(long roomId, String roomCode, String roomName, String location,
                                       int capacity, LocalTime businessOpenTime, LocalTime businessCloseTime,
                                       LocalDate bookingDate, int dayOfWeek, int attendees,
                                       boolean available, String unavailableCode, String unavailableReason,
                                       RequestedQuoteResponse requestedQuote, List<AvailableSlotResponse> slots,
                                       LocalDateTime quotedAt) {}
}
