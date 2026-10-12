package com.iminiapp.backend.hotel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class HotelModels {
    private HotelModels() {}

    public record ImageInput(String imageUrl, String imageType, Integer sortOrder, Boolean enabled) {}
    public record ImageResponse(long id, String imageUrl, String imageType, int sortOrder, boolean enabled) {}
    public record FacilityInput(String facilityCode, String name, Integer sortOrder) {}
    public record FacilityResponse(long id, String facilityCode, String name, int sortOrder) {}

    public record HotelUpsertRequest(String hotelCode, String name, String address, String contactPhone,
                                     String description, LocalTime checkInTime, LocalTime checkOutTime,
                                     String status, List<ImageInput> images) {}
    public record HotelResponse(long id, String hotelCode, String name, String address, String contactPhone,
                                String description, LocalTime checkInTime, LocalTime checkOutTime, String status,
                                long roomTypeCount, long roomCount, List<ImageResponse> images,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record RoomTypeUpsertRequest(Long hotelId, String typeCode, String name, String bedType,
                                        BigDecimal areaSqm, Integer maxGuests, Integer totalRooms,
                                        Integer lowStockThreshold, Long basePriceCent, String description,
                                        String coverUrl, String status, List<FacilityInput> facilities,
                                        List<ImageInput> images) {}
    public record RoomTypeResponse(long id, long hotelId, String hotelName, long resourceId, long skuId,
                                   String typeCode, String name, String bedType, BigDecimal areaSqm,
                                   int maxGuests, int totalRooms, int lowStockThreshold, long basePriceCent,
                                   String description, String coverUrl, String status, long physicalRoomCount,
                                   List<FacilityResponse> facilities, List<ImageResponse> images,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record RoomUpsertRequest(Long hotelId, Long roomTypeId, String roomNo, String floorNo,
                                    String status, String remark) {}
    public record RoomResponse(long id, long hotelId, String hotelName, long roomTypeId, String roomTypeName,
                               String roomNo, String floorNo, String status, String remark,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record PriceRuleUpsertRequest(Long roomTypeId, String ruleCode, String name, String ruleType,
                                         LocalDate startDate, LocalDate endDate, List<Integer> daysOfWeek,
                                         Long priceCent, Integer priority, String status) {}
    public record PriceRuleResponse(long id, long roomTypeId, String roomTypeName, String ruleCode, String name,
                                    String ruleType, LocalDate startDate, LocalDate endDate,
                                    List<Integer> daysOfWeek, long priceCent, int priority, String status,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record CalendarGenerateRequest(Long roomTypeId, LocalDate startDate, LocalDate endDate,
                                          Integer totalQuantity) {}
    public record InventoryDayUpsertRequest(Integer totalQuantity, Integer availableQuantity, Long priceCent) {}
    public record InventoryDayResponse(long inventoryId, long roomTypeId, LocalDate businessDate,
                                       int totalQuantity, int availableQuantity, int soldOrLockedQuantity,
                                       long priceCent, String priceSource, Long priceRuleId,
                                       String priceRuleName, boolean lowStock) {}
    public record CalendarGenerateResponse(long roomTypeId, LocalDate startDate, LocalDate endDate,
                                           int generatedDays, int updatedDays) {}

    public record HotelSummary(long id, String hotelCode, String name, String address, String contactPhone,
                               String description, LocalTime checkInTime, LocalTime checkOutTime,
                               String coverUrl, long roomTypeCount, Long startingPriceCent) {}
    public record HotelDetail(HotelSummary hotel, List<ImageResponse> images,
                              List<PublicRoomType> roomTypes) {}
    public record PublicRoomType(long id, long skuId, String typeCode, String name, String bedType,
                                 BigDecimal areaSqm, int maxGuests, int totalRooms, String description,
                                 String coverUrl, List<FacilityResponse> facilities, List<ImageResponse> images,
                                 Long nightlyFromPriceCent, Integer minimumAvailableQuantity,
                                 Long stayAmountCent, List<NightlyPrice> nightlyPrices) {}
    public record NightlyPrice(LocalDate businessDate, long priceCent, int availableQuantity) {}
}
