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
@RequestMapping("/api/v1/admin/cultural-store/after-sales")
public class AdminCulturalAfterSaleController {
    private final CulturalAfterSaleService service;
    public AdminCulturalAfterSaleController(CulturalAfterSaleService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResponse<AfterSaleResponse>> list(@RequestParam(required=false) String status,
            @RequestParam(required=false) String type, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, HttpServletRequest request) {
        return ApiResponse.success(service.adminList(status, type, page, pageSize), TraceIds.from(request));
    }
    @GetMapping("/{id}")
    public ApiResponse<AfterSaleResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(service.adminDetail(id), TraceIds.from(request));
    }
    @PostMapping("/{id}/approve")
    public ApiResponse<AfterSaleResponse> approve(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) ApproveRequest body, HttpServletRequest request) {
        return ApiResponse.success("售后审核通过", service.approve(CurrentPrincipal.from(request), id, key, body), TraceIds.from(request));
    }
    @PostMapping("/{id}/reject")
    public ApiResponse<AfterSaleResponse> reject(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestBody ReviewRequest body, HttpServletRequest request) {
        return ApiResponse.success("售后申请已拒绝", service.reject(CurrentPrincipal.from(request), id, key, body), TraceIds.from(request));
    }
    @PostMapping("/{id}/receive-return")
    public ApiResponse<AfterSaleResponse> receive(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) ReviewRequest body, HttpServletRequest request) {
        return ApiResponse.success("退货已签收，退款处理中", service.receiveReturn(CurrentPrincipal.from(request), id, key, body), TraceIds.from(request));
    }
    @PostMapping("/{id}/retry-refund")
    public ApiResponse<AfterSaleResponse> retryRefund(@PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) ReviewRequest body, HttpServletRequest request) {
        return ApiResponse.success("退款已重新提交至微信支付渠道",
                service.retryRefund(CurrentPrincipal.from(request), id, key, body), TraceIds.from(request));
    }
}
