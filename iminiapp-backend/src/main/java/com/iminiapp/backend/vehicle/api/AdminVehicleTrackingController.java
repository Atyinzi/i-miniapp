package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleTrackingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/admin/vehicle-dispatches")
public class AdminVehicleTrackingController {
    private final VehicleTrackingService service; public AdminVehicleTrackingController(VehicleTrackingService service){this.service=service;}
    @PostMapping("/{dispatchId}/locations") public ApiResponse<VehicleTripLogResponse> update(@PathVariable long dispatchId,@RequestBody VehicleLocationUpdateRequest body,HttpServletRequest request){return ApiResponse.success("车辆位置已上报",service.update(CurrentPrincipal.from(request),dispatchId,body),TraceIds.from(request));}
    @GetMapping("/{dispatchId}/trip-logs") public ApiResponse<List<VehicleTripLogResponse>> logs(@PathVariable long dispatchId,HttpServletRequest request){return ApiResponse.success(service.logs(dispatchId),TraceIds.from(request));}
}
