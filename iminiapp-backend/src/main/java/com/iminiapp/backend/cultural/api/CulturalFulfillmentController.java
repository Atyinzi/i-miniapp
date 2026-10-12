package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalFulfillmentModels.CarrierResponse;
import com.iminiapp.backend.cultural.api.CulturalFulfillmentModels.ShipmentResponse;
import com.iminiapp.backend.cultural.application.CulturalFulfillmentService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/cultural-store")
public class CulturalFulfillmentController {
    private final CulturalFulfillmentService service;

    public CulturalFulfillmentController(CulturalFulfillmentService service) { this.service = service; }

    @GetMapping("/carriers")
    public ApiResponse<List<CarrierResponse>> carriers(HttpServletRequest request) {
        return ApiResponse.success(service.carriers(true), TraceIds.from(request));
    }

    @GetMapping("/orders/{orderId}/shipment")
    public ApiResponse<ShipmentResponse> shipment(@PathVariable long orderId, HttpServletRequest request) {
        return ApiResponse.success(service.userShipment(CurrentPrincipal.from(request), orderId),
                TraceIds.from(request));
    }

    @PostMapping("/orders/{orderId}/confirm-receipt")
    public ApiResponse<ShipmentResponse> confirmReceipt(@PathVariable long orderId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        HttpServletRequest request) {
        return ApiResponse.success("确认收货成功", service.confirmReceipt(
                CurrentPrincipal.from(request), orderId, key), TraceIds.from(request));
    }
}
