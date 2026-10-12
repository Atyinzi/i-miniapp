package com.iminiapp.backend.vehicle.api;
import java.time.LocalDateTime;
public record VehicleUnavailablePeriodRequest(LocalDateTime startAt,LocalDateTime endAt,String reason){}
