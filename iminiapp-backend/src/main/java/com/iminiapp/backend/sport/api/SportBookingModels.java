package com.iminiapp.backend.sport.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class SportBookingModels {
    private SportBookingModels() {}

    public record PublicVenueResponse(long id, String venueCode, String name, String address,
                                      String contactPhone, String description, LocalTime openTime,
                                      LocalTime closeTime, List<PublicCourtResponse> courts) {}
    public record PublicCourtResponse(long id, long venueId, long courtTypeId, String courtTypeName,
                                      String courtCode, String name, String location, int capacity,
                                      long basePriceCent) {}
    public record AvailableSlotResponse(long courtId, LocalDate bookingDate, LocalTime startTime,
                                        LocalTime endTime, long priceCent, Long priceRuleId,
                                        String priceRuleName, boolean available) {}

    public record CreateBookingRequest(Long courtId, LocalDate bookingDate, LocalTime startTime,
                                       LocalTime endTime, String remark) {}
    public record BookingResponse(long bookingId, long orderId, String orderNo, String orderStatus,
                                  LocalDateTime expiresAt, long venueId, String venueName,
                                  long courtTypeId, String courtTypeName, long courtId, String courtName,
                                  LocalDate bookingDate, LocalTime startTime, LocalTime endTime,
                                  long priceCent, long originalPriceCent, long memberDiscountCent,
                                  Long memberAccountId, String memberLevelCode,
                                  Long priceRuleId, String priceRuleName,
                                  String bookingStatus, String remark, LocalDateTime createdAt,
                                  LocalDateTime confirmedAt, LocalDateTime cancelledAt,
                                  LocalDateTime refundedAt, LocalDateTime checkedInAt,
                                  LocalDateTime completedAt, Long verificationId,
                                  List<BookingStatusLogResponse> statusLogs) {}
    public record BookingStatusLogResponse(long id, String fromStatus, String toStatus, Long operatorId,
                                           String note, LocalDateTime createdAt) {}

    public record ClosureCreateRequest(Long courtId, LocalDate closureDate, LocalTime startTime,
                                       LocalTime endTime, String reason) {}
    public record ClosureResponse(long id, long courtId, String courtName, LocalDate closureDate,
                                  LocalTime startTime, LocalTime endTime, String reason,
                                  String status, Long createdBy, LocalDateTime createdAt) {}
}
