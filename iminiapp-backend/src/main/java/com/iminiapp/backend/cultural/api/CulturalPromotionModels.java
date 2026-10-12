package com.iminiapp.backend.cultural.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class CulturalPromotionModels {
    private CulturalPromotionModels() {}

    public record TargetInput(String targetType, Long targetId) {}
    public record PromotionUpsertRequest(String promotionCode, String name, String promotionType,
                                         Long thresholdAmountCent, Long reductionAmountCent,
                                         BigDecimal discountRate, Integer priority, Boolean exclusive,
                                         LocalDateTime startAt, LocalDateTime endAt, String status,
                                         List<TargetInput> targets) {}
    public record PromotionResponse(long id, String promotionCode, String name, String promotionType,
                                    long thresholdAmountCent, Long reductionAmountCent, BigDecimal discountRate,
                                    int priority, boolean exclusive, LocalDateTime startAt, LocalDateTime endAt,
                                    String status, List<TargetInput> targets) {}

    public record CouponUpsertRequest(String couponCode, String name, String couponType,
                                      Long thresholdAmountCent, Long discountAmountCent, BigDecimal discountRate,
                                      Integer issueTotal, Integer perUserLimit, Boolean stackable,
                                      LocalDateTime startAt, LocalDateTime endAt, String status,
                                      List<TargetInput> targets) {}
    public record CouponResponse(long id, String couponCode, String name, String couponType,
                                 long thresholdAmountCent, Long discountAmountCent, BigDecimal discountRate,
                                 Integer issueTotal, int issuedCount, int usedCount, int perUserLimit,
                                 boolean stackable, LocalDateTime startAt, LocalDateTime endAt,
                                 String status, List<TargetInput> targets) {}
    public record CouponIssueRequest(Long userId, Integer quantity) {}
    public record UserCouponResponse(long id, long couponId, String couponCode, String name, String couponType,
                                     long thresholdAmountCent, Long discountAmountCent, BigDecimal discountRate,
                                     boolean stackable, String status, LocalDateTime startAt, LocalDateTime endAt,
                                     LocalDateTime receivedAt, LocalDateTime usedAt, Long orderId) {}

    public record QuoteItem(Long skuId, Integer quantity) {}
    public record PricingQuoteRequest(List<QuoteItem> items, Long userCouponId) {}
    public record AppliedDiscount(Long promotionId, String promotionName, Long userCouponId, String couponName,
                                  long promotionDiscountCent, long couponDiscountCent) {}
    public record PricingQuoteResponse(long originalAmountCent, long promotionDiscountCent,
                                       long couponDiscountCent, long totalDiscountCent, long payableAmountCent,
                                       AppliedDiscount applied) {}
}
