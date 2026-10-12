package com.iminiapp.backend.hotel.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class HotelBookingModels {
    private HotelBookingModels() {}

    public record QuoteRequest(Long roomTypeId, LocalDate checkInDate, LocalDate checkOutDate,
                               Integer roomCount) {}
    public record NightQuote(LocalDate businessDate, long unitPriceCent, int availableQuantity,
                             long amountCent, boolean lowStock) {}
    public record QuoteResponse(long hotelId, String hotelName, long roomTypeId, long skuId,
                                String roomTypeName, LocalDate checkInDate, LocalDate checkOutDate,
                                int nights, int roomCount, long totalAmountCent,
                                int minimumAvailableQuantity, boolean lowStockWarning,
                                List<NightQuote> nightlyPrices) {}

    public record GuestInput(String name, String mobile, String idType, String idNo, Integer roomIndex) {}
    public record GuestResponse(long id, String name, String mobile, String idType,
                                String maskedIdNo, int roomIndex) {}
    public record CreateBookingRequest(Long roomTypeId, LocalDate checkInDate, LocalDate checkOutDate,
                                       Integer roomCount, Integer guestCount, String contactName,
                                       String contactMobile, LocalTime estimatedArrivalTime,
                                       String specialRequests, String remark, List<GuestInput> guests) {}
    public record ModifyBookingRequest(Long roomTypeId, LocalDate checkInDate, LocalDate checkOutDate,
                                       Integer roomCount, Integer guestCount, String contactName,
                                       String contactMobile, LocalTime estimatedArrivalTime,
                                       String specialRequests, String remark, List<GuestInput> guests) {}
    public record RefundReasonRequest(String reason) {}
    public record BookingResponse(long reservationId, String reservationNo, long orderId, String orderNo,
                                  String orderStatus, long hotelId, String hotelName, long roomTypeId,
                                  long skuId, String roomTypeName, LocalDate checkInDate,
                                  LocalDate checkOutDate, int nights, int roomCount, int guestCount,
                                  String contactName, String contactMobile, LocalTime estimatedArrivalTime,
                                  String specialRequests, long totalAmountCent, long payableAmountCent,
                                  long paidAmountCent, LocalDateTime paymentExpiresAt,
                                  Long previousReservationId, Long supersededByReservationId,
                                  List<GuestResponse> guests, List<NightQuote> nightlyPrices,
                                  LocalDateTime createdAt) {}

    public record InventoryAlertResponse(long id, long roomTypeId, String roomTypeName,
                                         LocalDate businessDate, Long orderId, String alertType,
                                         int availableQuantity, int thresholdQuantity,
                                         String status, String message, LocalDateTime createdAt,
                                         LocalDateTime resolvedAt) {}
}
