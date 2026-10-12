package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleFleetManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/admin")
public class AdminVehicleFleetController {
    private final VehicleFleetManagementService service;

    public AdminVehicleFleetController(VehicleFleetManagementService service) {
        this.service = service;
    }

    @PostMapping("/vehicle-models")
    public ApiResponse<VehicleModelResponse> createModel(@RequestBody VehicleModelUpsertRequest body,
                                                         HttpServletRequest request) {
        return ApiResponse.success("车型已创建",
                service.createModel(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @GetMapping("/vehicle-models")
    public ApiResponse<List<VehicleModelResponse>> models(HttpServletRequest request) {
        return ApiResponse.success(service.models(), TraceIds.from(request));
    }

    @PutMapping("/vehicle-models/{id}")
    public ApiResponse<VehicleModelResponse> updateModel(@PathVariable long id,
                                                         @RequestBody VehicleModelUpsertRequest body,
                                                         HttpServletRequest request) {
        return ApiResponse.success("车型已更新",
                service.updateModel(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @PostMapping("/vehicles")
    public ApiResponse<VehicleResponse> createVehicle(@RequestBody VehicleCreateRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.success("车辆已创建",
                service.createVehicle(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PostMapping("/vehicle-drivers")
    public ApiResponse<AdminVehicleDriverResponse> createDriver(@RequestBody VehicleDriverCreateRequest body,
                                                                 HttpServletRequest request) {
        return ApiResponse.success("司机已创建",
                service.createDriver(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PatchMapping("/vehicle-drivers/{id}/status")
    public ApiResponse<AdminVehicleDriverResponse> updateDriverStatus(@PathVariable long id,
                                                                       @RequestParam String status,
                                                                       HttpServletRequest request) {
        return ApiResponse.success("司机状态已更新",
                service.updateDriverStatus(CurrentPrincipal.from(request), id, status), TraceIds.from(request));
    }

    @PutMapping("/vehicle-drivers/{id}/account")
    public ApiResponse<AdminVehicleDriverResponse> bindDriverAccount(@PathVariable long id,
                                                                      @RequestBody DriverAccountBindingRequest body,
                                                                      HttpServletRequest request) {
        return ApiResponse.success("司机账号已绑定",
                service.bindDriverAccount(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @DeleteMapping("/vehicle-drivers/{id}/account")
    public ApiResponse<AdminVehicleDriverResponse> unbindDriverAccount(@PathVariable long id,
                                                                        HttpServletRequest request) {
        return ApiResponse.success("司机账号已解绑",
                service.unbindDriverAccount(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }

    @PostMapping("/vehicles/{id}/unavailable-periods")
    public ApiResponse<VehicleUnavailablePeriodResponse> addUnavailablePeriod(
            @PathVariable long id, @RequestBody VehicleUnavailablePeriodRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("不可租时段已创建",
                service.addUnavailable(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/vehicles/{id}/unavailable-periods")
    public ApiResponse<List<VehicleUnavailablePeriodResponse>> unavailablePeriods(
            @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(service.periods(id), TraceIds.from(request));
    }

    @PatchMapping("/vehicle-unavailable-periods/{id}/cancel")
    public ApiResponse<VehicleUnavailablePeriodResponse> cancelUnavailablePeriod(
            @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success("不可租时段已取消",
                service.cancelUnavailable(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }
}
