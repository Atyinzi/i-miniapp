package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalPromotionModels.*;
import com.iminiapp.backend.cultural.application.CulturalPromotionService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/cultural-store")
public class CulturalPromotionController {
    private final CulturalPromotionService service;

    public CulturalPromotionController(CulturalPromotionService service) { this.service = service; }

    @GetMapping("/coupons/claimable")
    public ApiResponse<List<CouponResponse>> claimable(HttpServletRequest request) {
        return ApiResponse.success(service.claimableCoupons(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @PostMapping("/coupons/{couponId}/claim")
    public ApiResponse<UserCouponResponse> claim(@PathVariable long couponId, HttpServletRequest request) {
        return ApiResponse.success("优惠券领取成功",
                service.claimCoupon(CurrentPrincipal.from(request), couponId), TraceIds.from(request));
    }

    @GetMapping("/coupons/mine")
    public ApiResponse<List<UserCouponResponse>> mine(@RequestParam(required = false) String status,
                                                      HttpServletRequest request) {
        return ApiResponse.success(service.myCoupons(CurrentPrincipal.from(request), status), TraceIds.from(request));
    }

    @PostMapping("/pricing/quote")
    public ApiResponse<PricingQuoteResponse> quote(@RequestBody PricingQuoteRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.success(service.quote(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
}
