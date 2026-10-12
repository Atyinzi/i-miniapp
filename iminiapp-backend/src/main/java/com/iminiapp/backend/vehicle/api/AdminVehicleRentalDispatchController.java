package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleRentalDispatchService;
import com.iminiapp.backend.vehicle.application.VehicleRentalSettlementService;
import com.iminiapp.backend.vehicle.application.VehicleRentalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminVehicleRentalDispatchController {
    private final VehicleRentalDispatchService service;
    private final VehicleRentalSettlementService settlementService;
    private final VehicleRentalService rentalService;
    public AdminVehicleRentalDispatchController(VehicleRentalDispatchService service, VehicleRentalSettlementService settlementService, VehicleRentalService rentalService) {
        this.service = service;
        this.settlementService = settlementService;
        this.rentalService = rentalService;
    }
    @GetMapping("/vehicle-rentals")
    public ApiResponse<java.util.List<VehicleRentalOrderResponse>> rentals(@RequestParam(required=false) String status,HttpServletRequest request){return ApiResponse.success(rentalService.listAdmin(CurrentPrincipal.from(request),status),TraceIds.from(request));}
    @GetMapping("/vehicle-rental-dispatches")
    public ApiResponse<java.util.List<VehicleRentalDispatchResponse>> dispatches(@RequestParam(required=false) String status,HttpServletRequest request){return ApiResponse.success(service.list(CurrentPrincipal.from(request),status),TraceIds.from(request));}
    @PostMapping("/vehicle-rentals/{orderId}/dispatch")
    public ApiResponse<VehicleRentalDispatchResponse> dispatch(@PathVariable long orderId, @RequestBody VehicleDispatchRequest body, HttpServletRequest request) { return ApiResponse.success("社会租赁已派车",service.dispatch(CurrentPrincipal.from(request),orderId,body),TraceIds.from(request)); }
    @PostMapping("/vehicle-rental-dispatches/{dispatchId}/accept")
    public ApiResponse<VehicleRentalDispatchResponse> accept(@PathVariable long dispatchId,HttpServletRequest request) { return ApiResponse.success("司机已接单",service.accept(CurrentPrincipal.from(request),dispatchId),TraceIds.from(request)); }
    @PostMapping("/vehicle-rental-dispatches/{dispatchId}/depart")
    public ApiResponse<VehicleRentalDispatchResponse> depart(@PathVariable long dispatchId,HttpServletRequest request) { return ApiResponse.success("车辆已出发",service.depart(CurrentPrincipal.from(request),dispatchId),TraceIds.from(request)); }
    @PostMapping("/vehicle-rental-dispatches/{dispatchId}/complete")
    public ApiResponse<VehicleSettlementResponse> complete(@PathVariable long dispatchId,@RequestBody VehicleTripCompleteRequest body,HttpServletRequest request) {
        VehicleReturnRequest returnRequest = new VehicleReturnRequest(null, body.endMileageKm(), body.fuelLiter(), body.remark(), java.util.List.of());
        return ApiResponse.success("社会租赁车辆已归还并结算", settlementService.returnVehicle(CurrentPrincipal.from(request),dispatchId,returnRequest),TraceIds.from(request));
    }
}
