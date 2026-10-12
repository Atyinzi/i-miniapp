package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleRentalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/app/vehicle-rentals")
public class VehicleRentalController {
    private final VehicleRentalService service;
    public VehicleRentalController(VehicleRentalService service) { this.service = service; }

    @GetMapping("/models")
    public ApiResponse<List<VehicleRentalModelResponse>> models(HttpServletRequest request) {
        return ApiResponse.success(service.listModels(), TraceIds.from(request));
    }

    @GetMapping("/models/{modelId}/vehicles")
    public ApiResponse<List<VehicleRentalVehicleResponse>> vehicles(@PathVariable long modelId,HttpServletRequest request) {
        return ApiResponse.success(service.listModelVehicles(modelId),TraceIds.from(request));
    }

    @GetMapping("/quote")
    public ApiResponse<VehicleRentalQuoteResponse> quote(@RequestParam long modelId, @RequestParam String rentalMode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
            @RequestParam(required = false) Integer passengerCount, @RequestParam(required = false) BigDecimal estimatedKm,
            HttpServletRequest request) {
        return ApiResponse.success(service.quote(modelId, rentalMode, startAt, endAt, passengerCount, estimatedKm), TraceIds.from(request));
    }

    @PostMapping
    public ApiResponse<VehicleRentalOrderResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody VehicleRentalCreateRequest body, HttpServletRequest request) {
        return ApiResponse.success("社会租赁订单已创建，等待支付", service.create(CurrentPrincipal.from(request), idempotencyKey, body), TraceIds.from(request));
    }

    @GetMapping
    public ApiResponse<List<VehicleRentalOrderResponse>> mine(HttpServletRequest request) {
        return ApiResponse.success(service.listMine(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @GetMapping("/{orderId}/status")
    public ApiResponse<VehicleRentalStatusResponse> status(
            @PathVariable long orderId, HttpServletRequest request) {
        return ApiResponse.success(service.status(CurrentPrincipal.from(request), orderId), TraceIds.from(request));
    }
}
