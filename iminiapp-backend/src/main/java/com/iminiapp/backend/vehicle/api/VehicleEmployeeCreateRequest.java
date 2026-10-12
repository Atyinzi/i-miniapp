package com.iminiapp.backend.vehicle.api;

public record VehicleEmployeeCreateRequest(String employeeNo, String name, String mobile,
                                           Long departmentId, String status, Long userId) {
}
