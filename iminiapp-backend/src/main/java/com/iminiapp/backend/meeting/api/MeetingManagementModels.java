package com.iminiapp.backend.meeting.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 管理端会议基础资料的请求及响应模型。 */
public final class MeetingManagementModels {
    private MeetingManagementModels() {}

    public record RoomUpsertRequest(String roomCode, String name, String location, Integer capacity,
                                    String description, LocalTime openTime, LocalTime closeTime, String status) {}
    public record RoomResponse(long id, String roomCode, String name, String location, int capacity,
                               String description, LocalTime openTime, LocalTime closeTime, String status,
                               long equipmentCount, long openingRuleCount, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record EquipmentUpsertRequest(Long roomId, String equipmentCode, String name, Integer quantity,
                                         String description, String status) {}
    public record EquipmentResponse(long id, long roomId, String roomName, String equipmentCode, String name,
                                    int quantity, String description, String status, LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {}

    public record OpeningRuleUpsertRequest(Long roomId, Integer dayOfWeek, LocalTime startTime,
                                           LocalTime endTime, Integer slotMinutes, String status) {}
    public record OpeningRuleResponse(long id, long roomId, String roomName, int dayOfWeek, LocalTime startTime,
                                      LocalTime endTime, int slotMinutes, String status, LocalDateTime createdAt,
                                      LocalDateTime updatedAt) {}

    public record PriceRuleUpsertRequest(Long roomId, String ruleCode, String name, LocalDate startDate,
                                         LocalDate endDate, Integer dayOfWeek, LocalTime startTime, LocalTime endTime,
                                         Integer minAttendees, Integer maxAttendees, Long priceCent, Integer priority,
                                         String status) {}
    public record PriceRuleResponse(long id, long roomId, String roomName, String ruleCode, String name,
                                    LocalDate startDate, LocalDate endDate, Integer dayOfWeek, LocalTime startTime,
                                    LocalTime endTime, Integer minAttendees, Integer maxAttendees, long priceCent,
                                    int priority, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record ServiceUpsertRequest(String serviceCode, String name, String serviceType, String pricingUnit,
                                       Long priceCent, String description, String status) {}
    public record ServiceResponse(long id, String serviceCode, String name, String serviceType, String pricingUnit,
                                  long priceCent, String description, String status, LocalDateTime createdAt,
                                  LocalDateTime updatedAt) {}
}
