package com.iminiapp.backend.cultural;

import com.iminiapp.backend.cultural.api.CulturalAfterSaleModels.*;
import com.iminiapp.backend.cultural.api.CulturalCommerceModels.*;
import com.iminiapp.backend.cultural.api.CulturalFulfillmentModels.ShipRequest;
import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.CulturalAfterSaleService;
import com.iminiapp.backend.cultural.application.CulturalCommerceService;
import com.iminiapp.backend.cultural.application.CulturalFulfillmentService;
import com.iminiapp.backend.cultural.application.CulturalStoreService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.payment.application.WechatPayV3Provider;
import com.iminiapp.backend.refund.application.RefundApplicationService;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class CulturalAfterSaleIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal("after-sale-admin", "售后管理员", "ADMIN", List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal("after-sale-user", "售后用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal OTHER = new AuthenticatedPrincipal("after-sale-other", "其他用户", "USER", List.of("ROLE_USER"));

    @Autowired CulturalAfterSaleService afterSaleService;
    @Autowired CulturalStoreService storeService;
    @Autowired CulturalCommerceService commerceService;
    @Autowired CulturalFulfillmentService fulfillmentService;
    @Autowired MockPaymentService paymentService;
    @Autowired RefundApplicationService refundApplicationService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean WechatPayV3Provider wechatPayV3Provider;

    @BeforeEach
    void enableWechatRefundProvider() {
        when(wechatPayV3Provider.availability())
                .thenReturn(new WechatPayV3Provider.Availability(true, "available for integration test"));
    }

    @Test
    void 未发货订单可仅退款并回补库存且请求幂等() {
        Fixture f = paidOrder("REFUND_ONLY");
        assertEquals(9, stock(f.skuId()));
        AfterSaleResponse applied = afterSaleService.apply(USER, f.orderId(), "as-apply-refund-only",
                new ApplyRequest("REFUND_ONLY", "不想要了", List.of("https://example.test/evidence-1.jpg")));
        AfterSaleResponse retry = afterSaleService.apply(USER, f.orderId(), "as-apply-refund-only",
                new ApplyRequest("REFUND_ONLY", "重复提交", List.of()));
        assertEquals(applied.id(), retry.id());
        assertEquals("PENDING_REVIEW", applied.status());

        AfterSaleResponse processing = afterSaleService.approve(ADMIN, applied.id(), "as-approve-refund-only",
                new ApproveRequest("同意退款", null));
        assertEquals("REFUNDING", processing.status());
        assertEquals("PROCESSING", processing.refundStatus());
        AfterSaleResponse completed = confirmWechatRefund(processing, 8000L);
        assertEquals("COMPLETED", completed.status());
        assertEquals("SUCCESS", completed.refundStatus());
        assertEquals("REFUNDED", commerceService.orderDetail(USER, f.orderId()).order().status());
        assertEquals(10, stock(f.skuId()));
        assertThrows(BusinessException.class, () -> afterSaleService.mineDetail(OTHER, applied.id()));
    }

    @Test
    void 已收货订单支持退货寄回商家签收入库后退款() {
        Fixture f = paidOrder("RETURN_REFUND");
        fulfillmentService.ship(ADMIN, f.orderId(), "as-ship-return",
                new ShipRequest("SF", "SF-AFTER-SALE-001", 10, null));
        fulfillmentService.confirmReceipt(USER, f.orderId(), "as-confirm-return");
        AfterSaleResponse applied = afterSaleService.apply(USER, f.orderId(), "as-apply-return",
                new ApplyRequest("RETURN_REFUND", "商品存在瑕疵", List.of()));
        AfterSaleResponse waiting = afterSaleService.approve(ADMIN, applied.id(), "as-approve-return",
                new ApproveRequest("请寄回", "内蒙古自治区包头市青山区售后仓"));
        assertEquals("WAITING_RETURN", waiting.status());
        AfterSaleResponse returned = afterSaleService.submitReturn(USER, applied.id(), "as-submit-return",
                new ReturnShipmentRequest("YTO", "YTO-RETURN-001", "已寄出"));
        assertEquals("RETURNED", returned.status());
        assertNotNull(returned.returnShipment());
        AfterSaleResponse received = afterSaleService.receiveReturn(ADMIN, applied.id(), "as-receive-return",
                new ReviewRequest("验货通过"));
        assertEquals("REFUNDING", received.status());
        assertNotNull(received.returnShipment().receivedAt());
        assertEquals(10, stock(f.skuId()));
        AfterSaleResponse completed = confirmWechatRefund(received, 8000L);
        assertEquals("COMPLETED", completed.status());
        assertEquals(10, stock(f.skuId()));
    }

    @Test
    void 待审核申请可拒绝或由用户取消且不能重复占用订单() {
        Fixture rejectedOrder = paidOrder("REJECT");
        AfterSaleResponse rejected = afterSaleService.apply(USER, rejectedOrder.orderId(), "as-apply-reject",
                new ApplyRequest("REFUND_ONLY", "申请退款", null));
        assertThrows(BusinessException.class, () -> afterSaleService.apply(USER, rejectedOrder.orderId(), "as-active-conflict",
                new ApplyRequest("REFUND_ONLY", "重复申请", null)));
        assertEquals("REJECTED", afterSaleService.reject(ADMIN, rejected.id(), "as-reject",
                new ReviewRequest("不符合退款条件")).status());

        Fixture cancelledOrder = paidOrder("CANCEL");
        AfterSaleResponse cancelled = afterSaleService.apply(USER, cancelledOrder.orderId(), "as-apply-cancel",
                new ApplyRequest("REFUND_ONLY", "申请后撤回", null));
        assertEquals("CANCELLED", afterSaleService.cancel(USER, cancelled.id(), "as-cancel").status());
        assertTrue(afterSaleService.adminList("CANCELLED", "REFUND_ONLY", 1, 20).total() >= 1);
    }

    private Fixture paidOrder(String suffix) {
        CategoryResponse category = storeService.createCategory(ADMIN, new CategoryUpsertRequest(
                "IT_AS_CATEGORY_" + suffix, "售后分类" + suffix, null, 1, "ACTIVE"));
        ProductDetail product = storeService.createProduct(ADMIN, new ProductUpsertRequest(
                "IT_AS_PRODUCT_" + suffix, "售后商品" + suffix, category.id(), null, null, null,
                12000L, 100, "ACTIVE", List.of(), List.of(new SkuCreateRequest(
                "IT_AS_SKU_" + suffix, "标准款", 8000L, "ACTIVE", "{}", 10, 2))));
        long skuId = product.skus().get(0).skuId();
        AddressResponse address = commerceService.createAddress(USER, new AddressUpsertRequest(
                "王五", "13800138000", "内蒙古自治区", "包头市", "青山区", "文化路1号", null, true));
        CulturalOrderResponse order = commerceService.checkout(USER, "it-as-order-" + suffix,
                new CheckoutRequest(address.id(), List.of(new CheckoutItem(skuId, 1)), "售后测试"));
        paymentService.pay(USER, order.order().id(), "it-as-pay-" + suffix);
        jdbcTemplate.update("""
                UPDATE bf_payment SET channel='WECHAT_PAY',transaction_id=?
                WHERE order_id=? AND status='SUCCESS'
                """, "WX-IT-AS-" + suffix, order.order().id());
        return new Fixture(order.order().id(), skuId);
    }
    private AfterSaleResponse confirmWechatRefund(AfterSaleResponse processing, long totalAmountCent) {
        String refundNo = jdbcTemplate.queryForObject(
                "SELECT refund_no FROM bf_refund WHERE id=?", String.class, processing.refundId());
        refundApplicationService.callbackFromWechat(
                "WX-REFUND-" + processing.refundId(), refundNo, "SUCCESS",
                processing.refundAmountCent(), totalAmountCent, "退款成功");
        return afterSaleService.adminDetail(processing.id());
    }
    private int stock(long skuId) { return jdbcTemplate.queryForObject("SELECT on_hand_quantity FROM bf_cultural_stock WHERE sku_id=?", Integer.class, skuId); }
    private record Fixture(long orderId, long skuId) {}
}
