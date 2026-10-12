package com.iminiapp.backend.vehicle.api;

public record VehicleEmployeeProfileResponse(boolean employee, Long employeeId, String employeeNo,
                                             String maskedName, Long departmentId,
                                             String departmentCode, String departmentName, String status) {
    public static VehicleEmployeeProfileResponse notBound() {
        return new VehicleEmployeeProfileResponse(false, null, null, null, null, null, null, null);
    }
}
