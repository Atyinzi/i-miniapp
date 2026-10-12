package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.api.CreateOrderRequest;
import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.order.application.OrderApplicationService;
import com.iminiapp.backend.payment.api.WechatJsapiPrepayResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.task.scheduling.enabled=false",
                "spring.main.allow-bean-definition-overriding=true"
        })
@ActiveProfiles("test")
@Transactional
class WechatPaymentDoublePaySafetyIT {
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "wechat-double-pay", "wechat payment test", "USER", List.of("ROLE_USER"),
            "WECHAT", "openid-wechat-double-pay");

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean
        @Primary
        RecordingProvider recordingWechatProvider() {
            return new RecordingProvider();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;
    @org.springframework.beans.factory.annotation.Autowired
    private OrderApplicationService orderService;
    @org.springframework.beans.factory.annotation.Autowired
    private WechatJsapiPaymentService prepayService;
    @org.springframework.beans.factory.annotation.Autowired
    private WechatPayCallbackService callbackService;
    @org.springframework.beans.factory.annotation.Autowired
    private RecordingProvider provider;

    private long skuId;

    @BeforeEach
    void prepareSku() {
        provider.reset();
        jdbcTemplate.update("""
                INSERT INTO bf_resource(resource_type,category_code,name,status,attributes)
                VALUES ('SCENIC_TICKET','WECHAT_PAY_SAFETY','wechat payment safety','ACTIVE',JSON_OBJECT())
                """);
        long resourceId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
                VALUES (?,CONCAT('WX_SAFE_',REPLACE(UUID(),'-','')),'wechat payment safety',188,'ACTIVE',JSON_OBJECT())
                """, resourceId);
        skuId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO bf_inventory(sku_id,business_date,time_slot,total_quantity,available_quantity,price_cent)
                VALUES (?,?,'',20,20,188)
                """, skuId, LocalDate.now());
    }

    @Test
    void differentIdempotencyKeysReuseOneChannelPrepayTransaction() {
        OrderResponse order = createOrder("double-key-order");

        WechatJsapiPrepayResponse first = prepayService.prepay(USER, order.id(), "prepay-key-a");
        WechatJsapiPrepayResponse second = prepayService.prepay(USER, order.id(), "prepay-key-b");

        assertEquals(first.outTradeNo(), second.outTradeNo());
        assertEquals(first.packageValue(), second.packageValue());
        assertEquals(1, provider.createCalls.get());
        assertEquals(1, provider.buildCalls.get());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_payment
                WHERE order_id=? AND channel='WECHAT_PAY' AND status='PENDING'
                """, Integer.class, order.id()));
    }

    @Test
    void differentKeyCannotRaceASecondChannelRequestWhileFirstPrepayIsUnfinished() {
        OrderResponse order = createOrder("initializing-order");
        jdbcTemplate.update("""
                INSERT INTO bf_payment(order_id,payment_no,channel,amount_cent,status,callback_payload)
                VALUES (?,'WX-IN-FLIGHT','WECHAT_PAY',188,'PENDING',
                    JSON_OBJECT('source','WECHAT_JSAPI','idempotencyKeyHash',SHA2('original-key',256)))
                """, order.id());

        assertThrows(BusinessException.class,
                () -> prepayService.prepay(USER, order.id(), "different-key"));
        assertEquals(0, provider.createCalls.get());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_payment
                WHERE order_id=? AND channel='WECHAT_PAY' AND status='PENDING'
                """, Integer.class, order.id()));
    }

    @Test
    void outOfOrderHistoricalDoubleCallbacksFulfilOnlyOnceAndRecordSecondCapture() {
        OrderResponse order = createOrder("historical-double-callback-order");
        jdbcTemplate.update("""
                INSERT INTO bf_payment(order_id,payment_no,channel,amount_cent,status,callback_payload)
                VALUES (?,'WX-HIST-A','WECHAT_PAY',188,'PENDING',JSON_OBJECT('source','LEGACY')),
                       (?,'WX-HIST-B','WECHAT_PAY',188,'PENDING',JSON_OBJECT('source','LEGACY'))
                """, order.id(), order.id());

        WechatPayV3Provider.CallbackHeaders headers = new WechatPayV3Provider.CallbackHeaders(
                "1786500000", "nonce", "signature", "serial", null);
        assertFalse(callbackService.handlePayment(headers, "callback-b").duplicate());
        assertFalse(callbackService.handlePayment(headers, "callback-a").duplicate());

        assertEquals("SUCCESS", jdbcTemplate.queryForObject(
                "SELECT status FROM bf_payment WHERE payment_no='WX-HIST-B'", String.class));
        assertEquals("DUPLICATE_SUCCESS", jdbcTemplate.queryForObject(
                "SELECT status FROM bf_payment WHERE payment_no='WX-HIST-A'", String.class));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_refund r
                JOIN bf_payment p ON p.id=r.automatic_capture_payment_id
                JOIN bf_wechat_refund_dispatch d ON d.refund_id=r.id
                WHERE p.payment_no='WX-HIST-A'
                  AND r.refund_kind='AUTO_DUPLICATE_CAPTURE'
                  AND r.status='PROCESSING'
                  AND d.dispatch_status='PENDING'
                  AND d.refund_amount_cent=188
                """, Integer.class));
        assertEquals("PAID", jdbcTemplate.queryForObject(
                "SELECT status FROM bf_order WHERE id=?", String.class, order.id()));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_audit_log
                WHERE action='ORDER_WECHAT_PAY' AND target_id=?
                """, Integer.class, String.valueOf(order.id())));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_audit_log
                WHERE action='ORDER_WECHAT_DUPLICATE_PAYMENT_CAPTURED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(detail,'$.orderId'))=?
                """, Integer.class, String.valueOf(order.id())));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_audit_log
                WHERE action='WECHAT_EXCEPTION_REFUND_ENQUEUED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(detail,'$.orderId'))=?
                """, Integer.class, String.valueOf(order.id())));

        assertTrue(callbackService.handlePayment(headers, "callback-a").duplicate());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_audit_log
                WHERE action='ORDER_WECHAT_DUPLICATE_PAYMENT_CAPTURED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(detail,'$.orderId'))=?
                """, Integer.class, String.valueOf(order.id())));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_refund r JOIN bf_payment p ON p.id=r.payment_id
                WHERE p.payment_no='WX-HIST-A'
                  AND r.refund_kind='AUTO_DUPLICATE_CAPTURE'
                """, Integer.class));
    }

    @Test
    void paidCallbackAfterLocalCancellationIsPersistedAndAutomaticallyRefundedWithoutFulfilment() {
        OrderResponse order = createOrder("late-after-cancel-order");
        jdbcTemplate.update("""
                INSERT INTO bf_payment(order_id,payment_no,channel,amount_cent,status,callback_payload)
                VALUES (?,'WX-LATE-CANCEL','WECHAT_PAY',188,'CLOSED',JSON_OBJECT('source','LEGACY'))
                """, order.id());
        jdbcTemplate.update("UPDATE bf_order SET status='CANCELLED' WHERE id=?", order.id());

        WechatPayV3Provider.CallbackHeaders headers = new WechatPayV3Provider.CallbackHeaders(
                "1786500000", "nonce", "signature", "serial", null);
        assertFalse(callbackService.handlePayment(headers, "callback-late-cancel").duplicate());

        assertEquals("LATE_SUCCESS", jdbcTemplate.queryForObject(
                "SELECT status FROM bf_payment WHERE payment_no='WX-LATE-CANCEL'", String.class));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM bf_order WHERE id=?", String.class, order.id()));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id
                WHERE i.order_id=?
                """, Integer.class, order.id()));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_refund r
                JOIN bf_wechat_refund_dispatch d ON d.refund_id=r.id
                WHERE r.order_id=? AND r.refund_kind='AUTO_LATE_CAPTURE'
                  AND r.status='PROCESSING' AND d.dispatch_status='PENDING'
                """, Integer.class, order.id()));
    }

    private OrderResponse createOrder(String idempotencyKey) {
        return orderService.create(USER, idempotencyKey,
                new CreateOrderRequest(null,
                        List.of(new CreateOrderRequest.Item(
                                skuId, 1, LocalDate.now(), "", Map.of())),
                        "wechat payment safety test"));
    }

    static final class RecordingProvider implements WechatPayV3Provider {
        private final AtomicInteger createCalls = new AtomicInteger();
        private final AtomicInteger buildCalls = new AtomicInteger();

        void reset() {
            createCalls.set(0);
            buildCalls.set(0);
        }

        @Override
        public Availability availability() {
            return new Availability(true, "available in test");
        }

        @Override
        public JsapiPrepayResult createJsapiPrepay(JsapiPrepayCommand command) {
            createCalls.incrementAndGet();
            return invocation(command.outTradeNo(), "prepay-" + command.outTradeNo(), "create");
        }

        @Override
        public JsapiPrepayResult buildJsapiInvocation(String outTradeNo, String prepayId) {
            buildCalls.incrementAndGet();
            return invocation(outTradeNo, prepayId, "reuse");
        }

        private JsapiPrepayResult invocation(String outTradeNo, String prepayId, String nonce) {
            return new JsapiPrepayResult(outTradeNo, "1786500000", nonce,
                    "prepay_id=" + prepayId, "RSA", "test-signature");
        }

        @Override
        public RefundResult requestRefund(RefundCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerifiedNotification verifyAndDecrypt(CallbackHeaders headers, String rawBody) {
            String suffix = switch (rawBody) {
                case "callback-a" -> "a";
                case "callback-b" -> "b";
                case "callback-late-cancel" -> "late-cancel";
                default -> throw new IllegalArgumentException("Unknown callback fixture");
            };
            String paymentNo = "late-cancel".equals(suffix)
                    ? "WX-LATE-CANCEL" : "WX-HIST-" + suffix.toUpperCase(java.util.Locale.ROOT);
            return new VerifiedNotification("notice-" + suffix, "TRANSACTION.SUCCESS", "transaction",
                    """
                    {"out_trade_no":"%s","transaction_id":"wechat-tx-%s",
                     "trade_state":"SUCCESS","trade_type":"JSAPI",
                     "amount":{"total":188,"currency":"CNY"}}
                    """.formatted(paymentNo, suffix));
        }
    }
}
