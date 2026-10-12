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
@RequestMapping("/api/v1/admin/cultural-store")
public class AdminCulturalPromotionController {
    private final CulturalPromotionService service;

    public AdminCulturalPromotionController(CulturalPromotionService service) { this.service = service; }

    @GetMapping("/promotions")
    public ApiResponse<List<PromotionResponse>> promotions(HttpServletRequest request) {
        return ApiResponse.success(service.promotions(), TraceIds.from(request));
    }

    @PostMapping("/promotions")
    public ApiResponse<PromotionResponse> createPromotion(@RequestBody PromotionUpsertRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.success("促销活动创建成功",
                service.createPromotion(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PutMapping("/promotions/{promotionId}")
    public ApiResponse<PromotionResponse> updatePromotion(@PathVariable long promotionId,
                                                          @RequestBody PromotionUpsertRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.success("促销活动更新成功",
                service.updatePromotion(CurrentPrincipal.from(request), promotionId, body), TraceIds.from(request));
    }

    @GetMapping("/coupons")
    public ApiResponse<List<CouponResponse>> coupons(HttpServletRequest request) {
        return ApiResponse.success(service.coupons(), TraceIds.from(request));
    }

    @PostMapping("/coupons")
    public ApiResponse<CouponResponse> createCoupon(@RequestBody CouponUpsertRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.success("优惠券模板创建成功",
                service.createCoupon(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PutMapping("/coupons/{couponId}")
    public ApiResponse<CouponResponse> updateCoupon(@PathVariable long couponId,
                                                    @RequestBody CouponUpsertRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.success("优惠券模板更新成功",
                service.updateCoupon(CurrentPrincipal.from(request), couponId, body), TraceIds.from(request));
    }

    @PostMapping("/coupons/{couponId}/issues")
    public ApiResponse<List<UserCouponResponse>> issueCoupon(@PathVariable long couponId,
                                                             @RequestBody CouponIssueRequest body,
                                                             HttpServletRequest request) {
        return ApiResponse.success("优惠券发放成功",
                service.issueCoupon(CurrentPrincipal.from(request), couponId, body), TraceIds.from(request));
    }
}
