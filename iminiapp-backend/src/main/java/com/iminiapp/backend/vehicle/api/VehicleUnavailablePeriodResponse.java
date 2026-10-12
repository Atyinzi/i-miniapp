package com.iminiapp.backend.vehicle.api;
import java.time.LocalDateTime;
public record VehicleUnavailablePeriodResponse(long id,long vehicleId,LocalDateTime startAt,LocalDateTime endAt,String reason,String status){}
