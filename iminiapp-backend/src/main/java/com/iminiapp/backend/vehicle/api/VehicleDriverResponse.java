package com.iminiapp.backend.vehicle.api;

/** 司机列表不返回姓名、手机号、驾驶证等敏感信息。 */
public record VehicleDriverResponse(long id, String driverNo, Long userId, String status) {
}
