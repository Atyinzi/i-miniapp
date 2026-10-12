package com.iminiapp.backend.sport.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class SportVenueModels {
    private SportVenueModels() {}

    public record VenueUpsertRequest(String venueCode, String name, String address, String contactPhone,
                                     String description, LocalTime openTime, LocalTime closeTime, String status) {}
    public record VenueResponse(long id, String venueCode, String name, String address, String contactPhone,
                                String description, LocalTime openTime, LocalTime closeTime, String status,
                                long courtTypeCount, long courtCount, LocalDateTime createdAt,
                                LocalDateTime updatedAt) {}

    public record CourtTypeUpsertRequest(Long venueId, String typeCode, String name, String description,
                                         Long basePriceCent, String status) {}
    public record CourtTypeResponse(long id, long venueId, String venueName, String typeCode, String name,
                                    String description, long basePriceCent, String status, long courtCount,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record CourtUpsertRequest(Long venueId, Long courtTypeId, String courtCode, String name,
                                     String location, Integer capacity, String status) {}
    public record CourtResponse(long id, long venueId, String venueName, long courtTypeId,
                                String courtTypeName, String courtCode, String name, String location,
                                int capacity, String status, long openingRuleCount,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record OpeningRuleUpsertRequest(Long courtId, Integer dayOfWeek, LocalTime startTime,
                                           LocalTime endTime, Integer slotMinutes, String status) {}
    public record OpeningRuleResponse(long id, long courtId, String courtName, int dayOfWeek,
                                      LocalTime startTime, LocalTime endTime, int slotMinutes,
                                      String status, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record PriceRuleUpsertRequest(Long venueId, Long courtTypeId, Long courtId, String ruleCode,
                                         String name, String ruleType, LocalDate startDate, LocalDate endDate,
                                         Integer dayOfWeek, LocalTime startTime, LocalTime endTime,
                                         Long priceCent, Integer priority, String status) {}
    public record PriceRuleResponse(long id, long venueId, String venueName, Long courtTypeId,
                                    String courtTypeName, Long courtId, String courtName, String ruleCode,
                                    String name, String ruleType, LocalDate startDate, LocalDate endDate,
                                    Integer dayOfWeek, LocalTime startTime, LocalTime endTime,
                                    long priceCent, int priority, String status,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
