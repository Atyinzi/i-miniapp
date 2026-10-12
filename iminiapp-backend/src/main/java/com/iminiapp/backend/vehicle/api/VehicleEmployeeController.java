package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleEmployeeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class VehicleEmployeeController {
    private final VehicleEmployeeService service;

    public VehicleEmployeeController(VehicleEmployeeService service) { this.service = service; }

    @GetMapping("/api/v1/app/vehicle-employees/me")
    public ApiResponse<VehicleEmployeeProfileResponse> profile(HttpServletRequest request) {
        return ApiResponse.success(service.profile(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @PostMapping("/api/v1/app/vehicle-employees/bind")
    public ApiResponse<VehicleEmployeeProfileResponse> selfBind(
            @RequestBody VehicleEmployeeSelfBindRequest body, HttpServletRequest request) {
        return ApiResponse.success("员工身份绑定成功", service.selfBind(CurrentPrincipal.from(request), body),
                TraceIds.from(request));
    }

    @GetMapping("/api/v1/admin/vehicle-departments")
    public ApiResponse<List<VehicleDepartmentResponse>> departments(HttpServletRequest request) {
        return ApiResponse.success(service.departments(), TraceIds.from(request));
    }

    @PostMapping("/api/v1/admin/vehicle-departments")
    public ApiResponse<VehicleDepartmentResponse> createDepartment(
            @RequestBody VehicleDepartmentUpsertRequest body, HttpServletRequest request) {
        return ApiResponse.success("部门已创建", service.createDepartment(CurrentPrincipal.from(request), body),
                TraceIds.from(request));
    }

    @GetMapping("/api/v1/admin/vehicle-employees")
    public ApiResponse<List<VehicleEmployeeResponse>> employees(
            @RequestParam(required = false) String status, HttpServletRequest request) {
        return ApiResponse.success(service.employees(status), TraceIds.from(request));
    }

    @GetMapping("/api/v1/admin/vehicle-driver-accounts")
    public ApiResponse<List<VehicleDriverResponse>> driverAccounts(HttpServletRequest request) {
        return ApiResponse.success(service.driverAccounts(), TraceIds.from(request));
    }

    @PostMapping("/api/v1/admin/vehicle-employees")
    public ApiResponse<VehicleEmployeeResponse> createEmployee(
            @RequestBody VehicleEmployeeCreateRequest body, HttpServletRequest request) {
        return ApiResponse.success("员工档案已创建", service.createEmployee(CurrentPrincipal.from(request), body),
                TraceIds.from(request));
    }

    @PutMapping("/api/v1/admin/vehicle-employees/{id}/account")
    public ApiResponse<VehicleEmployeeResponse> bindAccount(@PathVariable long id,
            @RequestBody VehicleEmployeeAccountBindingRequest body, HttpServletRequest request) {
        return ApiResponse.success("员工账号已绑定", service.bindAccount(CurrentPrincipal.from(request), id, body),
                TraceIds.from(request));
    }

    @DeleteMapping("/api/v1/admin/vehicle-employees/{id}/account")
    public ApiResponse<VehicleEmployeeResponse> unbindAccount(@PathVariable long id,
                                                               HttpServletRequest request) {
        return ApiResponse.success("员工账号已解绑", service.unbindAccount(CurrentPrincipal.from(request), id),
                TraceIds.from(request));
    }

    @PatchMapping("/api/v1/admin/vehicle-employees/{id}/status")
    public ApiResponse<VehicleEmployeeResponse> updateStatus(@PathVariable long id,
            @RequestParam String status, HttpServletRequest request) {
        return ApiResponse.success("员工状态已更新",
                service.updateStatus(CurrentPrincipal.from(request), id, status), TraceIds.from(request));
    }
}
