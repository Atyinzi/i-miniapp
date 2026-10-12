package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalAfterSaleModels.*;
import com.iminiapp.backend.cultural.application.CulturalAfterSaleService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/app/cultural-store")
public class CulturalAfterSaleController {
    private final CulturalAfterSaleService service;
    public CulturalAfterSaleController(CulturalAfterSaleService service) { this.service = service; }

    @PostMapping("/orders/{orderId}/after-sales")
    public ApiResponse<AfterSaleResponse> apply(@PathVariable long orderId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody ApplyRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("售后申请已提交", service.apply(CurrentPrincipal.from(request), orderId, key, body), TraceIds.from(request));
    }
    @GetMapping("/after-sales")
    public ApiResponse<PageResponse<AfterSaleResponse>> mine(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, HttpServletRequest request) {
        return ApiResponse.success(service.mine(CurrentPrincipal.from(request), page, pageSize), TraceIds.from(request));
    }
    @GetMapping("/after-sales/{id}")
    public ApiResponse<AfterSaleResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(service.mineDetail(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }
    @PostMapping("/after-sales/{id}/cancel")
    public ApiResponse<AfterSaleResponse> cancel(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return ApiResponse.success("售后申请已取消", service.cancel(CurrentPrincipal.from(request), id, key), TraceIds.from(request));
    }
    @PostMapping("/after-sales/{id}/return-shipment")
    public ApiResponse<AfterSaleResponse> submitReturn(@PathVariable long id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody ReturnShipmentRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("退货物流已提交", service.submitReturn(CurrentPrincipal.from(request), id, key, body), TraceIds.from(request));
    }
}
