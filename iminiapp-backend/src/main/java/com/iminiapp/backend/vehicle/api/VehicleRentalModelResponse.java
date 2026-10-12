package com.iminiapp.backend.vehicle.api;

/** 小程序社会租赁车型列表；具体时段可用数量以报价接口为准。 */
public record VehicleRentalModelResponse(long modelId, String modelCode, String modelName, int seatCount,
                                         Long halfDayCent, Long fullDayCent, Long monthCent, Long bareMonthCent) {
}
