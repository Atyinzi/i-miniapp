package com.iminiapp.backend.cultural;

import com.iminiapp.backend.cultural.api.CulturalCommerceModels.*;
import com.iminiapp.backend.cultural.api.CulturalPromotionModels.*;
import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.CulturalCommerceService;
import com.iminiapp.backend.cultural.application.CulturalPromotionService;
import com.iminiapp.backend.cultural.application.CulturalStoreService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.task.scheduling.enabled=false"
        })
@Transactional
class CulturalPromotionIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "cultural-promotion-admin", "文创营销管理员", "ADMIN", List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "cultural-promotion-user", "文创营销用户", "USER", List.of("ROLE_USER"));

    @Autowired private CulturalStoreService storeService;
    @Autowired private CulturalCommerceService commerceService;
    @Autowired private CulturalPromotionService promotionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 自动选择最优活动并叠加优惠券下单取消后返券() {
        Fixture fixture = fixture("STACK");
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusDays(7);
        promotionService.createPromotion(ADMIN, new PromotionUpsertRequest(
                "IT_PROMO_FULL", "满150减20", "FULL_REDUCTION", 15000L, 2000L, null,
                10, false, start, end, "ACTIVE", List.of(new TargetInput("ALL", 0L))));
        promotionService.createPromotion(ADMIN, new PromotionUpsertRequest(
                "IT_PROMO_DISCOUNT", "九五折", "DISCOUNT", 0L, null, new BigDecimal("0.9500"),
                20, false, start, end, "ACTIVE", List.of(new TargetInput("PRODUCT", fixture.productId()))));
        CouponResponse coupon = promotionService.createCoupon(ADMIN, new CouponUpsertRequest(
                "IT_COUPON_30", "满100减30券", "FULL_REDUCTION", 10000L, 3000L, null,
                100, 1, true, start, end, "ACTIVE", List.of(new TargetInput("SKU", fixture.skuId()))));
        UserCouponResponse userCoupon = promotionService.claimCoupon(USER, coupon.id());

        PricingQuoteResponse quote = promotionService.quote(USER, new PricingQuoteRequest(
                List.of(new QuoteItem(fixture.skuId(), 2)), userCoupon.id()));
        assertEquals(20000, quote.originalAmountCent());
        assertEquals(2000, quote.promotionDiscountCent());
        assertEquals(3000, quote.couponDiscountCent());
        assertEquals(15000, quote.payableAmountCent());
        assertThrows(BusinessException.class, () -> promotionService.claimCoupon(USER, coupon.id()));

        AddressResponse address = commerceService.createAddress(USER, new AddressUpsertRequest(
                "张三", "13800138000", "内蒙古自治区", "包头市", "青山区", "i某小程序产业园1号", null, true));
        CulturalOrderResponse order = commerceService.checkout(USER, "it-promotion-order",
                new CheckoutRequest(address.id(), List.of(new CheckoutItem(fixture.skuId(), 2)),
                        "优惠订单", userCoupon.id()));
        assertEquals(5000, order.order().totalAmountCent() - order.order().payableAmountCent());
        assertEquals(15000, order.order().payableAmountCent());
        assertEquals("USED", promotionService.myCoupons(USER, null).get(0).status());

        commerceService.cancelOrder(USER, order.order().id());
        UserCouponResponse returned = promotionService.myCoupons(USER, null).get(0);
        assertEquals("UNUSED", returned.status());
        assertNull(returned.orderId());
        assertEquals(0, promotionService.coupons().stream().filter(c -> c.id() == coupon.id()).findFirst().orElseThrow().usedCount());
    }

    @Test
    void 不可叠加优惠券被拒绝且过期券自动更新状态() {
        Fixture fixture = fixture("EXCLUSIVE");
        LocalDateTime now = LocalDateTime.now();
        promotionService.createPromotion(ADMIN, new PromotionUpsertRequest(
                "IT_PROMO_ACTIVE", "全场满减", "FULL_REDUCTION", 5000L, 500L, null,
                1, false, now.minusHours(1), now.plusDays(1), "ACTIVE", List.of()));
        CouponResponse nonStackable = promotionService.createCoupon(ADMIN, new CouponUpsertRequest(
                "IT_COUPON_NO_STACK", "不可叠加券", "DISCOUNT", 0L, null, new BigDecimal("0.9000"),
                10, 1, false, now.minusHours(1), now.plusDays(1), "ACTIVE", List.of()));
        UserCouponResponse claimed = promotionService.claimCoupon(USER, nonStackable.id());
        assertThrows(BusinessException.class, () -> promotionService.quote(USER, new PricingQuoteRequest(
                List.of(new QuoteItem(fixture.skuId(), 1)), claimed.id())));

        CouponResponse expiredTemplate = promotionService.createCoupon(ADMIN, new CouponUpsertRequest(
                "IT_COUPON_EXPIRED", "已过期测试券", "FULL_REDUCTION", 0L, 100L, null,
                10, 2, true, now.minusDays(2), now.minusDays(1), "ACTIVE", List.of()));
        long userId = jdbcTemplate.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class,
                USER.databaseOpenId());
        promotionService.issueCoupon(ADMIN, expiredTemplate.id(), new CouponIssueRequest(userId, 1));
        assertTrue(promotionService.myCoupons(USER, "EXPIRED").stream()
                .anyMatch(item -> item.couponId() == expiredTemplate.id()));
        assertThrows(BusinessException.class, () -> promotionService.updateCoupon(ADMIN, expiredTemplate.id(),
                new CouponUpsertRequest("IT_COUPON_EXPIRED", "修改已发券", "FULL_REDUCTION", 0L, 200L,
                        null, 10, 2, true, now.minusDays(2), now.plusDays(1), "ACTIVE", List.of())));
    }

    private Fixture fixture(String suffix) {
        CategoryResponse category = storeService.createCategory(ADMIN, new CategoryUpsertRequest(
                "IT_PROMO_CATEGORY_" + suffix, "营销测试分类" + suffix, null, 1, "ACTIVE"));
        ProductDetail product = storeService.createProduct(ADMIN, new ProductUpsertRequest(
                "IT_PROMO_PRODUCT_" + suffix, "营销测试商品" + suffix, category.id(), null, null, null,
                12000L, 100, "ACTIVE", List.of(), List.of(new SkuCreateRequest(
                "IT_PROMO_SKU_" + suffix, "标准款", 10000L, "ACTIVE", "{}", 20, 2))));
        return new Fixture(product.id(), product.skus().get(0).skuId());
    }

    private record Fixture(long productId, long skuId) {}
}
