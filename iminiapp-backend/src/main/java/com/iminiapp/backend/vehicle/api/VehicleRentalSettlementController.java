package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleRentalSettlementService;
import com.iminiapp.backend.vehicle.application.VehicleSupplementWechatPaymentService;
import com.iminiapp.backend.payment.api.WechatJsapiPrepayResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class VehicleRentalSettlementController {
    private final VehicleRentalSettlementService service;
    private final VehicleSupplementWechatPaymentService supplementPaymentService;
    public VehicleRentalSettlementController(VehicleRentalSettlementService service,
            VehicleSupplementWechatPaymentService supplementPaymentService) {
        this.service = service;
        this.supplementPaymentService = supplementPaymentService;
    }

    @PostMapping("/api/v1/admin/vehicle-rental-dispatches/{dispatchId}/return")
    public ApiResponse<VehicleSettlementResponse> returnVehicle(@PathVariable long dispatchId,
            @RequestBody VehicleReturnRequest body, HttpServletRequest request) {
        return ApiResponse.success("车辆已归还并生成结算单", service.returnVehicle(CurrentPrincipal.from(request), dispatchId, body), TraceIds.from(request));
    }

    @GetMapping("/api/v1/app/vehicle-rentals/{orderId}/settlement")
    public ApiResponse<VehicleSettlementResponse> detail(@PathVariable long orderId, HttpServletRequest request) {
        return ApiResponse.success(service.detailForUser(CurrentPrincipal.from(request), orderId), TraceIds.from(request));
    }

    @PostMapping("/api/v1/app/vehicle-rentals/{orderId}/settlement/wechat-pay/jsapi-prepay")
    public ApiResponse<WechatJsapiPrepayResponse> supplementPrepay(@PathVariable long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request) {
        return ApiResponse.success(supplementPaymentService.prepay(
                CurrentPrincipal.from(request), orderId, idempotencyKey), TraceIds.from(request));
    }

}
