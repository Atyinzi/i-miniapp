package com.iminiapp.backend.cultural;

import com.iminiapp.backend.cultural.api.CulturalCommerceModels.*;
import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.CulturalCommerceService;
import com.iminiapp.backend.cultural.application.CulturalStoreService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.application.AdminOrderQueryService;
import com.iminiapp.backend.order.application.ExpiredOrderService;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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
class CulturalCommerceIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "cultural-commerce-admin", "文创交易管理员", "ADMIN", List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "cultural-commerce-user", "文创测试用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal OTHER = new AuthenticatedPrincipal(
            "cultural-commerce-other", "其他用户", "USER", List.of("ROLE_USER"));

    @Autowired private CulturalStoreService storeService;
    @Autowired private CulturalCommerceService commerceService;
    @Autowired private MockPaymentService paymentService;
    @Autowired private ExpiredOrderService expiredOrderService;
    @Autowired private AdminOrderQueryService adminOrderQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 购物车结算锁定库存取消后释放且地址不可越权() {
        Fixture fixture = fixture("CART", 10);
        AddressResponse address = address(USER, true);
        commerceService.putCartItem(USER, new CartItemUpsertRequest(fixture.skuId(), 3, true));

        CulturalOrderResponse created = commerceService.checkout(USER, "it-commerce-cart-order",
                new CheckoutRequest(address.id(), List.of(), "购物车结算"));
        StockResponse locked = stock(fixture.skuId());

        assertEquals("CULTURAL_PRODUCT", created.order().orderType());
        assertEquals(3, created.order().items().get(0).quantity());
        assertNull(created.order().items().get(0).serviceDate());
        assertEquals(7, locked.availableQuantity());
        assertEquals(3, locked.lockedQuantity());
        assertTrue(commerceService.cart(USER).isEmpty());
        assertEquals(created.order().id(), adminOrderQueryService.detail(created.order().id()).id());
        assertThrows(BusinessException.class, () -> commerceService.updateAddress(OTHER, address.id(),
                new AddressUpsertRequest("越权用户", "13800138001", "内蒙古自治区", "包头市", "青山区",
                        "无权修改地址", null, false)));

        CulturalOrderResponse cancelled = commerceService.cancelOrder(USER, created.order().id());
        StockResponse released = stock(fixture.skuId());
        assertEquals("CANCELLED", cancelled.order().status());
        assertEquals(10, released.availableQuantity());
        assertEquals(0, released.lockedQuantity());
        assertEquals(10, released.onHandQuantity());
    }

    @Test
    void 直接购买支付后正式扣减实体库存且重复支付不重复扣减() {
        Fixture fixture = fixture("PAY", 8);
        AddressResponse address = address(USER, false);
        CulturalOrderResponse created = commerceService.checkout(USER, "it-commerce-direct-order",
                new CheckoutRequest(address.id(), List.of(new CheckoutItem(fixture.skuId(), 2)), "立即购买"));

        paymentService.pay(USER, created.order().id(), "it-commerce-pay");
        paymentService.pay(USER, created.order().id(), "it-commerce-pay-retry");
        StockResponse paid = stock(fixture.skuId());

        assertEquals(6, paid.onHandQuantity());
        assertEquals(6, paid.availableQuantity());
        assertEquals(0, paid.lockedQuantity());
        Integer saleLogs = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_cultural_stock_log
                WHERE sku_id=? AND change_type='SALE_DEDUCT'
                """, Integer.class, fixture.skuId());
        assertEquals(1, saleLogs);
    }

    @Test
    void 待支付订单超时后自动释放文创库存() {
        Fixture fixture = fixture("EXPIRE", 5);
        AddressResponse address = address(USER, false);
        CulturalOrderResponse created = commerceService.checkout(USER, "it-commerce-expire-order",
                new CheckoutRequest(address.id(), List.of(new CheckoutItem(fixture.skuId(), 4)), "超时释放"));
        jdbcTemplate.update("UPDATE bf_order SET expires_at=? WHERE id=?",
                LocalDateTime.now().minusMinutes(1), created.order().id());

        assertTrue(expiredOrderService.expireOverdue() >= 1);
        StockResponse stock = stock(fixture.skuId());
        assertEquals(5, stock.availableQuantity());
        assertEquals(0, stock.lockedQuantity());
    }

    private Fixture fixture(String suffix, int stock) {
        CategoryResponse category = storeService.createCategory(ADMIN,
                new CategoryUpsertRequest("IT_COMMERCE_" + suffix, "交易测试分类" + suffix, null, 1, "ACTIVE"));
        ProductDetail product = storeService.createProduct(ADMIN, new ProductUpsertRequest(
                "IT_COMMERCE_PRODUCT_" + suffix, "交易测试商品" + suffix, category.id(), "测试", null,
                "<p>测试</p>", 12000L, 100, "ACTIVE", List.of(), List.of(
                new SkuCreateRequest("IT_COMMERCE_SKU_" + suffix, "标准款", 9900L, "ACTIVE", "{}", stock, 1))));
        return new Fixture(product.id(), product.skus().get(0).skuId());
    }

    private AddressResponse address(AuthenticatedPrincipal principal, boolean isDefault) {
        return commerceService.createAddress(principal, new AddressUpsertRequest(
                "张三", "13800138000", "内蒙古自治区", "包头市", "青山区", "装备制造产业园1号", "014030", isDefault));
    }

    private StockResponse stock(long skuId) {
        return storeService.stocks(null, "IT_COMMERCE_SKU", 1, 100).items().stream()
                .filter(item -> item.skuId() == skuId).findFirst().orElseThrow();
    }

    private record Fixture(long productId, long skuId) {}
}
