package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.DriverVehicleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/driver")
public class DriverVehicleController {
    private final DriverVehicleService service;
    public DriverVehicleController(DriverVehicleService service){this.service=service;}
    @GetMapping("/profile") public ApiResponse<DriverProfileResponse> profile(HttpServletRequest request){return ApiResponse.success(service.profile(CurrentPrincipal.from(request)),TraceIds.from(request));}
    @GetMapping("/tasks") public ApiResponse<List<DriverTaskResponse>> tasks(@RequestParam(required=false) String status,HttpServletRequest request){return ApiResponse.success(service.tasks(CurrentPrincipal.from(request),status),TraceIds.from(request));}
    @GetMapping("/tasks/{dispatchId}") public ApiResponse<DriverTaskResponse> task(@PathVariable long dispatchId,HttpServletRequest request){return ApiResponse.success(service.task(CurrentPrincipal.from(request),dispatchId),TraceIds.from(request));}
    @PostMapping("/tasks/{dispatchId}/accept") public ApiResponse<DriverTaskResponse> accept(@PathVariable long dispatchId,HttpServletRequest request){return ApiResponse.success("司机已接单",service.accept(CurrentPrincipal.from(request),dispatchId),TraceIds.from(request));}
    @PostMapping("/tasks/{dispatchId}/depart") public ApiResponse<DriverTaskResponse> depart(@PathVariable long dispatchId,HttpServletRequest request){return ApiResponse.success("车辆已出发",service.depart(CurrentPrincipal.from(request),dispatchId),TraceIds.from(request));}
    @PostMapping("/tasks/{dispatchId}/complete") public ApiResponse<DriverTaskResponse> complete(@PathVariable long dispatchId,@RequestBody VehicleTripCompleteRequest body,HttpServletRequest request){return ApiResponse.success("行程已完成",service.complete(CurrentPrincipal.from(request),dispatchId,body),TraceIds.from(request));}
    @PostMapping("/tasks/{dispatchId}/locations") public ApiResponse<VehicleTripLogResponse> location(@PathVariable long dispatchId,@RequestBody VehicleLocationUpdateRequest body,HttpServletRequest request){return ApiResponse.success("位置已上报",service.updateLocation(CurrentPrincipal.from(request),dispatchId,body),TraceIds.from(request));}
}
