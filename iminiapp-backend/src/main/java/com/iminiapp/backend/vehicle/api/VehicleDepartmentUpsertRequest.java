package com.iminiapp.backend.vehicle.api;

public record VehicleDepartmentUpsertRequest(String departmentCode, String departmentName,
                                             Long parentId, String status) {
}
