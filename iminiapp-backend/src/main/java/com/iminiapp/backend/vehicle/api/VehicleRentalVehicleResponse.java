package com.iminiapp.backend.vehicle.api;
import java.math.BigDecimal;
public record VehicleRentalVehicleResponse(long id,long modelId,String modelName,String imageUrl,String configuration,String status,BigDecimal mileageKm){}
