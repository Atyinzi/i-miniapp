package com.iminiapp.backend.cultural;

import com.iminiapp.backend.cultural.api.CulturalCommerceModels.*;
import com.iminiapp.backend.cultural.api.CulturalFulfillmentModels.*;
import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.CulturalCommerceService;
import com.iminiapp.backend.cultural.application.CulturalFulfillmentService;
import com.iminiapp.backend.cultural.application.CulturalStoreService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
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
class CulturalFulfillmentIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "cultural-fulfillment-admin", "文创发货管理员", "ADMIN", List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "cultural-fulfillment-user", "文创收货用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal OTHER = new AuthenticatedPrincipal(
            "cultural-fulfillment-other", "其他用户", "USER", List.of("ROLE_USER"));

    @Autowired private CulturalStoreService storeService;
    @Autowired private CulturalCommerceService commerceService;
    @Autowired private CulturalFulfillmentService fulfillmentService;
    @Autowired private MockPaymentService paymentService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 已支付订单可备货发货跟踪异常恢复并由用户确认收货() {
        long orderId = paidOrder("FLOW", "SF-FLOW-001");
        ShipmentResponse prepared = fulfillmentService.prepare(ADMIN, orderId, "it-prepare-flow",
                new PrepareRequest("商品检查完成"));
        ShipmentResponse prepareRetry = fulfillmentService.prepare(ADMIN, orderId, "it-prepare-flow",
                new PrepareRequest("重复请求"));
        assertEquals("PENDING", prepared.status());
        assertEquals(prepared.id(), prepareRetry.id());

        ShipmentResponse shipped = fulfillmentService.ship(ADMIN, orderId, "it-ship-flow",
                new ShipRequest("SF", "SF-FLOW-001", 10, "顺丰发货"));
        ShipmentResponse shipRetry = fulfillmentService.ship(ADMIN, orderId, "it-ship-flow",
                new ShipRequest("SF", "SF-FLOW-001", 10, "重复请求"));
        assertEquals("SHIPPED", shipped.status());
        assertEquals(1, shipRetry.trackingEvents().size());
        assertTrue(shipped.trackingUrl().contains("SF-FLOW-001"));

        ShipmentResponse exception = fulfillmentService.appendTracking(ADMIN, orderId, "it-track-exception",
                new TrackingEventRequest("EXCEPTION", "天气原因中转延误", "呼和浩特转运中心",
                        LocalDateTime.now(), "MANUAL", "EXT-FLOW-1"));
        assertEquals("EXCEPTION", exception.status());
        assertTrue(exception.exceptionFlag());
        ShipmentResponse recovered = fulfillmentService.appendTracking(ADMIN, orderId, "it-track-recover",
                new TrackingEventRequest("IN_TRANSIT", "运输已恢复", "包头转运中心",
                        LocalDateTime.now().plusMinutes(1), "MANUAL", "EXT-FLOW-2"));
        assertEquals("IN_TRANSIT", recovered.status());
        assertFalse(recovered.exceptionFlag());

        ShipmentResponse received = fulfillmentService.confirmReceipt(USER, orderId, "it-confirm-flow");
        assertEquals("DELIVERED", received.status());
        assertEquals("COMPLETED", commerceService.orderDetail(USER, orderId).order().status());
        assertEquals("COMPLETED", fulfillmentService.adminOrderDetail(orderId).order().status());
        assertThrows(BusinessException.class, () -> fulfillmentService.userShipment(OTHER, orderId));
    }

    @Test
    void 到期且无异常的运单可自动确认收货() {
        long orderId = paidOrder("AUTO", "JD-AUTO-001");
        fulfillmentService.ship(ADMIN, orderId, "it-ship-auto",
                new ShipRequest("JD", "JD-AUTO-001", 1, null));
        jdbcTemplate.update("UPDATE bf_shipment SET auto_receive_at=? WHERE order_id=?",
                LocalDateTime.now().minusMinutes(1), orderId);

        assertTrue(fulfillmentService.autoReceiveOverdue() >= 1);
        ShipmentResponse shipment = fulfillmentService.userShipment(USER, orderId);
        assertEquals("DELIVERED", shipment.status());
        assertTrue(shipment.trackingEvents().stream()
                .anyMatch(event -> event.eventDescription().contains("自动确认收货")));
    }

    @Test
    void 多个已支付订单支持原子批量发货并可在后台筛选() {
        long first = paidOrder("BATCH_A", "YTO-BATCH-001");
        long second = paidOrder("BATCH_B", "YTO-BATCH-002");
        List<ShipmentResponse> result = fulfillmentService.batchShip(ADMIN, "it-batch-ship",
                new BatchShipRequest(List.of(
                        new BatchShipItem(first, "YTO", "YTO-BATCH-001", 7, "批量发货A"),
                        new BatchShipItem(second, "YTO", "YTO-BATCH-002", 7, "批量发货B"))));

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(item -> "SHIPPED".equals(item.status())));
        assertTrue(fulfillmentService.adminOrders("SHIPPED", "YTO-BATCH", 1, 20).total() >= 2);
    }

    private long paidOrder(String suffix, String reference) {
        CategoryResponse category = storeService.createCategory(ADMIN, new CategoryUpsertRequest(
                "IT_FULFILL_CATEGORY_" + suffix, "发货测试分类" + suffix, null, 1, "ACTIVE"));
        ProductDetail product = storeService.createProduct(ADMIN, new ProductUpsertRequest(
                "IT_FULFILL_PRODUCT_" + suffix, "发货测试商品" + suffix, category.id(), null, null, null,
                10000L, 100, "ACTIVE", List.of(), List.of(new SkuCreateRequest(
                "IT_FULFILL_SKU_" + suffix, "标准款", 8000L, "ACTIVE", "{}", 20, 2))));
        AddressResponse address = commerceService.createAddress(USER, new AddressUpsertRequest(
                "李四", "13900139000", "内蒙古自治区", "包头市", "青山区", "i某小程序产业园2号", null, true));
        CulturalOrderResponse order = commerceService.checkout(USER, "it-fulfill-order-" + suffix,
                new CheckoutRequest(address.id(), List.of(new CheckoutItem(product.skus().get(0).skuId(), 1)),
                        "发货测试"));
        paymentService.pay(USER, order.order().id(), "it-fulfill-pay-" + suffix);
        return order.order().id();
    }
}
