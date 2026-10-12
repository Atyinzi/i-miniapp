package com.iminiapp.backend.vehicle.api;

public record VehicleDepartmentResponse(long id, String departmentCode, String departmentName,
                                        Long parentId, String status) {
}
