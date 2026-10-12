package com.iminiapp.backend.vehicle.api;
public record VehicleTariffUpdateRequest(Long halfDayCent, Long fullDayCent, Long monthCent, Long bareMonthCent,
                                         Long overtimeCentPerHour, Long overDistanceCentPerKm, Boolean enabled) {}
