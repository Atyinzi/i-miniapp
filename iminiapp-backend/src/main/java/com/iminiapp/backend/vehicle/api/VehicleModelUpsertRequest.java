package com.iminiapp.backend.vehicle.api;
public record VehicleModelUpsertRequest(String modelCode,String name,Integer seatCount,String fuelType,String status){}
