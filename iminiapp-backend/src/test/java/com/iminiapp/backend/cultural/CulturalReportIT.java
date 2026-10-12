package com.iminiapp.backend.cultural;

import com.iminiapp.backend.cultural.api.CulturalAfterSaleModels.*;
import com.iminiapp.backend.cultural.api.CulturalCommerceModels.*;
import com.iminiapp.backend.cultural.api.CulturalReportModels.*;
import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.payment.application.WechatPayV3Provider;
import com.iminiapp.backend.refund.application.RefundApplicationService;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class CulturalReportIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal("report-admin", "报表管理员", "ADMIN", List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal("report-user", "报表用户", "USER", List.of("ROLE_USER"));
    @Autowired CulturalReportService reportService;
    @Autowired CulturalStoreService storeService;
    @Autowired CulturalCommerceService commerceService;
    @Autowired CulturalAfterSaleService afterSaleService;
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
    void 报表可统计销售退款商品排行库存并导出CSV() {
        CategoryResponse category = storeService.createCategory(ADMIN, new CategoryUpsertRequest(
                "IT_REPORT_CATEGORY", "报表分类", null, 1, "ACTIVE"));
        ProductDetail product = storeService.createProduct(ADMIN, new ProductUpsertRequest(
                "IT_REPORT_PRODUCT", "报表商品", category.id(), null, null, null, 10000L, 100,
                "ACTIVE", List.of(), List.of(new SkuCreateRequest(
                "IT_REPORT_SKU", "标准款", 8000L, "ACTIVE", "{}", 20, 5))));
        long skuId = product.skus().get(0).skuId();
        AddressResponse address = commerceService.createAddress(USER, new AddressUpsertRequest(
                "赵六", "13700137000", "内蒙古自治区", "包头市", "青山区", "报表路1号", null, true));
        long first = paidOrder(address.id(), skuId, "A");
        paidOrder(address.id(), skuId, "B");
        AfterSaleResponse afterSale = afterSaleService.apply(USER, first, "report-as-apply",
                new ApplyRequest("REFUND_ONLY", "报表退款", List.of()));
        AfterSaleResponse processing = afterSaleService.approve(
                ADMIN, afterSale.id(), "report-as-approve", new ApproveRequest("同意", null));
        confirmWechatRefund(processing, 8000L);

        LocalDate today = LocalDate.now();
        OverviewResponse overview = reportService.overview(today, today);
        assertTrue(overview.paidOrderCount() >= 2);
        assertTrue(overview.soldQuantity() >= 2);
        assertTrue(overview.grossSalesCent() >= 16000);
        assertTrue(overview.refundAmountCent() >= 8000);
        assertEquals(overview.grossSalesCent() - overview.refundAmountCent(), overview.netSalesCent());
        DashboardResponse dashboard = reportService.dashboard(today, today);
        assertEquals(1, dashboard.salesTrend().size());
        assertTrue(dashboard.topProducts().stream().anyMatch(item -> item.productId() == product.id() && item.soldQuantity() == 2));
        assertTrue(dashboard.afterSaleSummary().completedCount() >= 1);
        assertTrue(reportService.inventory("IT_REPORT_SKU", false, 1, 20).items().stream()
                .anyMatch(item -> item.skuId() == skuId && item.onHandQuantity() == 19));
        assertEquals(1, reportService.stockMovements(today, today).size());
        String csv = new String(reportService.salesCsv(today, today), StandardCharsets.UTF_8);
        assertTrue(csv.contains("订单号"));
        assertTrue(csv.contains("报表用户") || csv.contains("退款"));
    }

    @Test
    void 报表拒绝反向日期和超过一年范围() {
        LocalDate today = LocalDate.now();
        assertThrows(BusinessException.class, () -> reportService.overview(today, today.minusDays(1)));
        assertThrows(BusinessException.class, () -> reportService.salesTrend(today.minusDays(367), today));
        assertThrows(BusinessException.class, () -> reportService.topProducts(today, today, 101));
    }

    private long paidOrder(long addressId, long skuId, String suffix) {
        CulturalOrderResponse order = commerceService.checkout(USER, "it-report-order-" + suffix,
                new CheckoutRequest(addressId, List.of(new CheckoutItem(skuId, 1)), "报表测试"));
        paymentService.pay(USER, order.order().id(), "it-report-pay-" + suffix);
        jdbcTemplate.update("""
                UPDATE bf_payment SET channel='WECHAT_PAY',transaction_id=?
                WHERE order_id=? AND status='SUCCESS'
                """, "WX-IT-REPORT-" + suffix, order.order().id());
        return order.order().id();
    }

    private void confirmWechatRefund(AfterSaleResponse processing, long totalAmountCent) {
        String refundNo = jdbcTemplate.queryForObject(
                "SELECT refund_no FROM bf_refund WHERE id=?", String.class, processing.refundId());
        refundApplicationService.callbackFromWechat(
                "WX-REPORT-REFUND-" + processing.refundId(), refundNo, "SUCCESS",
                processing.refundAmountCent(), totalAmountCent, "退款成功");
    }
}
