package com.iminiapp.backend.hotel.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class HotelOperationModels {
    private HotelOperationModels() {}

    public record CheckInCredentialResponse(long reservationId, String reservationNo, String checkInCode,
                                            String status, LocalDateTime validFrom, LocalDateTime validUntil) {}
    public record CheckInRequest(List<Long> roomIds, Boolean allowEarlyCheckIn, String note) {}
    public record CheckInByCodeRequest(String checkInCode, List<Long> roomIds,
                                       Boolean allowEarlyCheckIn, String note) {}
    public record CheckInByIdCardRequest(String idNo, List<Long> roomIds,
                                         Boolean allowEarlyCheckIn, String note) {}
    public record ChangeRoomRequest(Long fromRoomId, Long toRoomId, String reason) {}
    public record NoteRequest(String note) {}
    public record ExtendStayRequest(LocalDate newCheckOutDate, String remark) {}
    public record ExtendStayResponse(long reservationId, LocalDate previousCheckOutDate,
                                     LocalDate newCheckOutDate, int addedNights,
                                     long additionalAmountCent, long newOrderAmountCent) {}
    public record RoomAssignmentResponse(long id, long roomId, String roomNo, String floorNo,
                                         String assignmentStatus, LocalDateTime assignedFrom,
                                         LocalDateTime assignedUntil, String changeReason) {}

    public record ChargeInput(String chargeType, String description, Integer quantity, Long unitPriceCent) {}
    public record ChargeResponse(long id, String chargeType, String description, int quantity,
                                 long unitPriceCent, long amountCent, LocalDateTime createdAt) {}
    public record CheckoutQuoteResponse(long reservationId, long roomAmountCent, long extraChargeCent,
                                        long finalAmountCent, long orderPaidCent,
                                        long additionalDueCent, long refundableCent,
                                        List<ChargeResponse> charges) {}
    public record CheckoutRequest(List<ChargeInput> charges, Long additionalPaidCent, Long refundCent,
                                  String paymentMethod, String remark) {}
    public record SettlementResponse(long id, String settlementNo, long reservationId,
                                     long roomAmountCent, long extraChargeCent, long orderPaidCent,
                                     long additionalPaidCent, long refundCent, long finalAmountCent,
                                     String paymentMethod, String status, String remark,
                                     LocalDateTime settledAt) {}

    public record AdminStaySummary(long reservationId, String reservationNo, long orderId, String orderNo,
                                   String orderStatus, String stayStatus, long hotelId, String hotelName,
                                   long roomTypeId, String roomTypeName, LocalDate checkInDate,
                                   LocalDate checkOutDate, int roomCount, int guestCount,
                                   String contactName, String contactMobile, long payableAmountCent,
                                   long paidAmountCent, LocalDateTime actualCheckInAt,
                                   LocalDateTime actualCheckOutAt, LocalDateTime createdAt) {}
    public record AdminStayDetail(AdminStaySummary stay, List<RoomAssignmentResponse> roomAssignments,
                                  List<ChargeResponse> charges, SettlementResponse settlement) {}

    public record RoomStatusResponse(long roomId, String roomNo, long roomTypeId,
                                     String roomTypeName, String status, LocalDateTime updatedAt) {}
    public record OperationWarning(long reservationId, String reservationNo, String warningType,
                                   String message, LocalDate checkInDate, LocalDate checkOutDate,
                                   String stayStatus) {}
    public record HotelOperationReport(LocalDate startDate, LocalDate endDate,
                                       long availableRoomNights, long soldRoomNights,
                                       long occupiedRoomNights, double occupancyRate,
                                       long roomRevenueCent, long extraRevenueCent,
                                       long averageDailyRateCent, long revParCent,
                                       long checkInCount, long checkOutCount, long noShowCount) {}
}
