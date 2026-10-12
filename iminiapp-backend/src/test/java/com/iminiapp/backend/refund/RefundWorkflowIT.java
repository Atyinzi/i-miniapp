package com.iminiapp.backend.refund;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.api.CreateOrderRequest;
import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.order.application.OrderApplicationService;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.refund.api.RefundApplicationResponse;
import com.iminiapp.backend.refund.application.RefundApplicationService;
import com.iminiapp.backend.scenic.api.AdminScenicSpotCreateRequest;
import com.iminiapp.backend.scenic.api.AdminScenicSpotResponse;
import com.iminiapp.backend.scenic.api.AdminScenicTicketCreateRequest;
import com.iminiapp.backend.scenic.api.AdminScenicTicketResponse;
import com.iminiapp.backend.scenic.api.ScenicInventorySetupRequest;
import com.iminiapp.backend.scenic.application.AdminScenicManagementService;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class RefundWorkflowIT {
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal("refund-user", "退款测试用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal("refund-admin", "退款审核员", "ADMIN", List.of("ROLE_ADMIN"));
    @Autowired AdminScenicManagementService scenicService;
    @Autowired OrderApplicationService orderService;
    @Autowired MockPaymentService paymentService;
    @Autowired VerificationTicketService ticketService;
    @Autowired RefundApplicationService refundService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 退款须审核并在渠道成功回调后释放库存和作废电子票() {
        AdminScenicSpotResponse spot = scenicService.createScenicSpot(ADMIN, new AdminScenicSpotCreateRequest(
                "IT_REFUND_SCENIC", "退款测试景区", "TEST", "测试", null, "测试地址", null, null,
                "0472-1000000", "测试", "09:00-17:00", 60, "ACTIVE"));
        AdminScenicTicketResponse ticket = scenicService.createTicket(ADMIN, spot.id(), new AdminScenicTicketCreateRequest(
                "IT_REFUND_TICKET", "退款测试票", "ADULT", 5000L, "ACTIVE", "测试游客", "当日使用",
                "未核销可退", "凭票入园", 1, 5, false, false));
        scenicService.setupInventory(ADMIN, ticket.skuId(), LocalDate.now(), "", "refund-it-stock",
                new ScenicInventorySetupRequest(10, 5000L));

        OrderResponse order = createPaidOrder(ticket.skuId(), 2, "refund-it-order-1", "refund-it-pay-1");
        assertEquals(8, available(ticket.skuId()));
        assertEquals(2, ticketService.listOwnedTickets(USER, order.id()).size());

        RefundApplicationResponse applied = refundService.apply(USER, order.id(), "refund-it-apply-1", "行程取消");
        assertEquals("PENDING_REVIEW", applied.status());
        assertEquals("PAID", orderService.detail(USER, order.id()).status());
        assertEquals(applied.id(), refundService.apply(USER, order.id(), "refund-it-apply-1", "行程取消").id());

        RefundApplicationResponse processing = refundService.approve(ADMIN, applied.id(), "符合退票规则");
        assertEquals("PROCESSING", processing.status());
        assertEquals("REFUNDING", orderService.detail(USER, order.id()).status());
        assertEquals(8, available(ticket.skuId()));

        RefundApplicationResponse completed = refundService.callbackFromWechat(
                processing.channelRefundId(), processing.refundNo(), "SUCCESS",
                processing.amountCent(), processing.amountCent(), "channel refund succeeded");
        assertEquals("SUCCESS", completed.status());
        assertNotNull(completed.refundedAt());
        assertEquals("REFUNDED", orderService.detail(USER, order.id()).status());
        assertEquals(10, available(ticket.skuId()));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id WHERE i.order_id=? AND v.status='VOID'", Integer.class, order.id()));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_refund_status_log WHERE refund_id=?", Integer.class, applied.id()));
        assertEquals(completed.id(), refundService.callbackFromWechat(
                processing.channelRefundId(), processing.refundNo(), "SUCCESS",
                processing.amountCent(), processing.amountCent(), "duplicate callback").id());
        assertEquals(10, available(ticket.skuId()));

        OrderResponse rejectedOrder = createPaidOrder(ticket.skuId(), 1, "refund-it-order-2", "refund-it-pay-2");
        RefundApplicationResponse rejectedApplication = refundService.apply(USER, rejectedOrder.id(), "refund-it-apply-2", "临时申请");
        RefundApplicationResponse rejected = refundService.reject(ADMIN, rejectedApplication.id(), "不符合退票规则");
        assertEquals("REJECTED", rejected.status());
        assertEquals("PAID", orderService.detail(USER, rejectedOrder.id()).status());
        assertEquals(9, available(ticket.skuId()));
    }

    private OrderResponse createPaidOrder(long skuId, int quantity, String orderKey, String paymentKey) {
        OrderResponse order = orderService.create(USER, orderKey, new CreateOrderRequest(null, List.of(
                new CreateOrderRequest.Item(skuId, quantity, LocalDate.now(), "", Map.of())), "退款测试"));
        paymentService.pay(USER, order.id(), paymentKey);
        return order;
    }

    private int available(long skuId) {
        return jdbcTemplate.queryForObject("SELECT available_quantity FROM bf_inventory WHERE sku_id=? AND business_date=? AND time_slot=''", Integer.class, skuId, LocalDate.now());
    }
}
