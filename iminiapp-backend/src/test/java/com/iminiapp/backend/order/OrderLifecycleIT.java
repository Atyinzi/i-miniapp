package com.iminiapp.backend.order;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.api.CreateOrderRequest;
import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.order.application.OrderApplicationService;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.refund.api.RefundApplicationResponse;
import com.iminiapp.backend.refund.application.RefundApplicationService;
import com.iminiapp.backend.verification.api.VerificationTicketResponse;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.task.scheduling.enabled=false"
        })
@Transactional
class OrderLifecycleIT {

    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "integration-user", "集成测试用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal OPS = new AuthenticatedPrincipal(
            "integration-ops", "集成测试运维", "OPS", List.of("ROLE_OPS"));
    private static final AuthenticatedPrincipal WECHAT_USER = new AuthenticatedPrincipal(
            "wx-integration-user", "微信集成测试用户", "USER", List.of("ROLE_USER"),
            "WECHAT", "openid-order-integration-test");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OrderApplicationService orderService;
    @Autowired
    private MockPaymentService paymentService;
    @Autowired
    private RefundApplicationService refundService;
    @Autowired
    private VerificationTicketService ticketService;

    private long skuId;
    private long inventoryId;

    @BeforeEach
    void 准备独立测试商品和库存() {
        jdbcTemplate.update("""
                INSERT INTO bf_resource (resource_type, category_code, name, description, status, attributes)
                VALUES ('SCENIC_TICKET', 'INTEGRATION_TEST', '集成测试门票', '仅供自动化测试使用', 'ACTIVE', JSON_OBJECT())
                """);
        long resourceId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO bf_resource_sku (resource_id, sku_code, name, price_cent, status, attributes)
                VALUES (?, 'IT_HOTEL_ROOM_001', '集成测试房型', 10000, 'ACTIVE', JSON_OBJECT())
                """, resourceId);
        skuId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO bf_inventory
                (sku_id, business_date, time_slot, total_quantity, available_quantity, price_cent)
                VALUES (?, ?, '', 10, 10, 10000)
                """, skuId, LocalDate.now());
        inventoryId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void 创建支付退款后库存恢复且电子票作废() {
        int initialInventory = availableInventory();
        OrderResponse order = createOrder("it-refund-order");
        assertEquals(initialInventory - 1, availableInventory());
        assertEquals("PENDING_PAYMENT", order.status());

        paymentService.pay(USER, order.id(), "it-refund-pay");
        List<VerificationTicketResponse> tickets = ticketService.listOwnedTickets(USER, order.id());
        assertEquals(1, tickets.size());
        assertEquals("UNUSED", tickets.get(0).status());

        RefundApplicationResponse applied = refundService.apply(USER, order.id(), "it-refund", "集成测试退款");
        RefundApplicationResponse processing = refundService.approve(OPS, applied.id(), "审核通过");
        RefundApplicationResponse refund = refundService.callbackFromWechat(
                processing.channelRefundId(), processing.refundNo(), "SUCCESS",
                processing.amountCent(), processing.amountCent(), "integration refund succeeded");

        assertEquals("SUCCESS", refund.status());
        assertEquals("REFUNDED", orderService.detail(USER, order.id()).status());
        assertEquals(initialInventory, availableInventory());
        assertEquals("VOID", ticketStatus(tickets.get(0).verificationId()));
    }

    @Test
    void 创建支付核销后订单完成() {
        OrderResponse order = createOrder("it-verify-order");
        paymentService.pay(USER, order.id(), "it-verify-pay");
        VerificationTicketResponse ticket = ticketService.listOwnedTickets(USER, order.id()).get(0);
        assertNotNull(ticket.code());

        VerificationTicketResponse consumed = ticketService.consume(OPS, ticket.code());

        assertEquals("USED", consumed.status());
        assertNotNull(consumed.verifiedAt());
        assertEquals("COMPLETED", orderService.detail(USER, order.id()).status());
    }

    @Test
    void 微信用户创建订单时保存真实OpenId() {
        CreateOrderRequest.Item item = new CreateOrderRequest.Item(
                skuId, 1, LocalDate.now(), "", Map.of());

        OrderResponse order = orderService.create(
                WECHAT_USER,
                "it-wechat-order",
                new CreateOrderRequest(null, List.of(item), "微信身份数据库映射测试"));

        String openId = jdbcTemplate.queryForObject("""
                SELECT u.wechat_openid
                FROM bf_order o JOIN bf_user u ON u.id = o.user_id
                WHERE o.id = ?
                """, String.class, order.id());
        assertEquals("openid-order-integration-test", openId);
    }

    private OrderResponse createOrder(String idempotencyKey) {
        CreateOrderRequest.Item item = new CreateOrderRequest.Item(
                skuId, 1, LocalDate.now(), "", Map.of());
        return orderService.create(
                USER,
                idempotencyKey,
                new CreateOrderRequest(null, List.of(item), "数据库闭环集成测试"));
    }

    private int availableInventory() {
        return jdbcTemplate.queryForObject(
                "SELECT available_quantity FROM bf_inventory WHERE id = ?", Integer.class, inventoryId);
    }

    private String ticketStatus(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bf_verification WHERE id = ?", String.class, ticketId);
    }
}
