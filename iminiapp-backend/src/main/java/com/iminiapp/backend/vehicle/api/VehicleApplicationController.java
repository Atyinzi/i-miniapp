package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 内部用车第一阶段：申请、审批、派车、接单、出车与完成。 */
@RestController
public class VehicleApplicationController {
    private final VehicleApplicationService service;

    public VehicleApplicationController(VehicleApplicationService service) { this.service = service; }

    @PostMapping("/api/v1/app/vehicle-applications")
    public ApiResponse<VehicleApplicationResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                            @RequestBody VehicleApplicationCreateRequest body,
                                                            HttpServletRequest request) {
        return ApiResponse.success("内部用车申请已提交", service.create(CurrentPrincipal.from(request), idempotencyKey, body), TraceIds.from(request));
    }

    @GetMapping("/api/v1/app/vehicle-applications")
    public ApiResponse<List<VehicleApplicationResponse>> mine(HttpServletRequest request) {
        return ApiResponse.success(service.listMine(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @GetMapping("/api/v1/app/vehicle-applications/{applicationId}")
    public ApiResponse<VehicleApplicationResponse> detail(@PathVariable long applicationId, HttpServletRequest request) {
        return ApiResponse.success(service.detailMine(CurrentPrincipal.from(request), applicationId), TraceIds.from(request));
    }

    @PostMapping("/api/v1/app/vehicle-applications/{applicationId}/cancel")
    public ApiResponse<VehicleApplicationResponse> cancel(@PathVariable long applicationId,
                                                           HttpServletRequest request) {
        return ApiResponse.success("内部用车申请已取消",
                service.cancelMine(CurrentPrincipal.from(request), applicationId), TraceIds.from(request));
    }

    @GetMapping("/api/v1/admin/vehicles")
    public ApiResponse<List<VehicleResponse>> vehicles(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Long modelId,
                                                        HttpServletRequest request) {
        return ApiResponse.success(service.listVehicles(CurrentPrincipal.from(request), status, modelId), TraceIds.from(request));
    }

    @GetMapping("/api/v1/admin/vehicle-drivers")
    public ApiResponse<List<VehicleDriverResponse>> drivers(@RequestParam(required = false) String status, HttpServletRequest request) {
        return ApiResponse.success(service.listDrivers(CurrentPrincipal.from(request), status), TraceIds.from(request));
    }

    @GetMapping("/api/v1/admin/vehicle-applications")
    public ApiResponse<List<VehicleApplicationResponse>> applications(@RequestParam(required = false) String status, HttpServletRequest request) {
        return ApiResponse.success(service.listAll(CurrentPrincipal.from(request), status), TraceIds.from(request));
    }

    @PostMapping("/api/v1/admin/vehicle-applications/{applicationId}/review")
    public ApiResponse<VehicleApplicationResponse> review(@PathVariable long applicationId, @RequestBody VehicleApplicationReviewRequest body, HttpServletRequest request) {
        return ApiResponse.success("内部用车申请已审核", service.review(CurrentPrincipal.from(request), applicationId, body), TraceIds.from(request));
    }

    @PostMapping("/api/v1/admin/vehicle-applications/{applicationId}/dispatch")
    public ApiResponse<VehicleApplicationResponse> dispatch(@PathVariable long applicationId, @RequestBody VehicleDispatchRequest body, HttpServletRequest request) {
        return ApiResponse.success("车辆已派出，等待司机接单", service.dispatch(CurrentPrincipal.from(request), applicationId, body), TraceIds.from(request));
    }

    @PostMapping("/api/v1/admin/vehicle-dispatches/{dispatchId}/accept")
    public ApiResponse<VehicleApplicationResponse> accept(@PathVariable long dispatchId, HttpServletRequest request) {
        return ApiResponse.success("司机已接单", service.accept(CurrentPrincipal.from(request), dispatchId), TraceIds.from(request));
    }

    @PostMapping("/api/v1/admin/vehicle-dispatches/{dispatchId}/depart")
    public ApiResponse<VehicleApplicationResponse> depart(@PathVariable long dispatchId, HttpServletRequest request) {
        return ApiResponse.success("车辆已出发", service.depart(CurrentPrincipal.from(request), dispatchId), TraceIds.from(request));
    }

    @PostMapping("/api/v1/admin/vehicle-dispatches/{dispatchId}/complete")
    public ApiResponse<VehicleApplicationResponse> complete(@PathVariable long dispatchId, @RequestBody VehicleTripCompleteRequest body, HttpServletRequest request) {
        return ApiResponse.success("行程已完成", service.complete(CurrentPrincipal.from(request), dispatchId, body), TraceIds.from(request));
    }
}
