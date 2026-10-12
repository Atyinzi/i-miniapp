package com.iminiapp.backend.vehicle.api;

import java.time.LocalDateTime;

public record VehicleEmployeeResponse(long id, String employeeNo, String maskedName, String maskedMobile,
                                      long departmentId, String departmentCode, String departmentName,
                                      Long userId, String status, LocalDateTime boundAt) {
}
