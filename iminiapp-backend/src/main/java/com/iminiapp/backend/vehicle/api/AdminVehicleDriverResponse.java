package com.iminiapp.backend.vehicle.api;
public record AdminVehicleDriverResponse(long id,String driverNo,String maskedName,String maskedMobile,
                                         Long userId,String status){}
