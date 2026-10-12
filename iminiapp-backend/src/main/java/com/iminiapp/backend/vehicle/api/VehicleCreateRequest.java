package com.iminiapp.backend.vehicle.api;
import java.math.BigDecimal;
public record VehicleCreateRequest(Long modelId,String vehicleNo,String plateNo,String imageUrl,String configuration,String status,BigDecimal mileageKm){}
