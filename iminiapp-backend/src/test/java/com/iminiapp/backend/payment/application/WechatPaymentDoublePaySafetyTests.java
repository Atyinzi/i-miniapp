package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.api.PaymentResponse;
import com.iminiapp.backend.payment.api.WechatJsapiPrepayResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WechatPaymentDoublePaySafetyTests {
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "wechat-safety", "wechat safety", "USER", List.of("ROLE_USER"),
            "WECHAT", "openid-wechat-safety");

    @Test
    void twoDifferentIdempotencyKeysReuseTheOriginalPrepay() {
        PrepayJdbc jdbc = new PrepayJdbc();
        RecordingProvider provider = new RecordingProvider();
        WechatJsapiPaymentService service = new WechatJsapiPaymentService(
                jdbc, provider, transactions());

        WechatJsapiPrepayResponse first = service.prepay(USER, 17L, "key-a");
        WechatJsapiPrepayResponse second = service.prepay(USER, 17L, "key-b");

        assertEquals(first.outTradeNo(), second.outTradeNo());
        assertEquals(first.packageValue(), second.packageValue());
        assertEquals(1, provider.createCalls.get());
        assertEquals(1, provider.buildCalls.get());
        assertEquals(1, jdbc.insertCount);
    }

    @Test
    void differentKeyDuringInitializationIsRejectedBeforeASecondChannelCall() {
        PrepayJdbc jdbc = new PrepayJdbc();
        jdbc.payment = new LocalPayment(
                "WX-IN-FLIGHT", "PENDING", 188, null,
                "5d3c8949b893aeb7a6ab43af5c2d2f0b95e220dae4ee02d01f6e8a78d445c8b7",
                null, null);
        RecordingProvider provider = new RecordingProvider();
        WechatJsapiPaymentService service = new WechatJsapiPaymentService(
                jdbc, provider, transactions());

        assertThrows(BusinessException.class,
                () -> service.prepay(USER, 17L, "different-key"));

        assertEquals(0, provider.createCalls.get());
        assertEquals(0, provider.buildCalls.get());
        assertEquals(0, jdbc.insertCount);
    }

    @Test
    void outOfOrderDoubleCallbacksFulfilOnceAndRecordLateCaptureIdempotently() {
        SettlementJdbc jdbc = new SettlementJdbc();
        VerificationTicketService tickets = mock(VerificationTicketService.class);
        WechatRefundDispatchService dispatch = new WechatRefundDispatchService(
                jdbc, new RecordingProvider());
        WechatPaymentSettlementService service = new WechatPaymentSettlementService(
                jdbc, tickets, dispatch);

        PaymentResponse first = service.settle("WX-B", "tx-b", 188, "notice-b");
        PaymentResponse late = service.settle("WX-A", "tx-a", 188, "notice-a");
        PaymentResponse lateRetry = service.settle("WX-A", "tx-a", 188, "notice-a-retry");

        assertEquals("SUCCESS", first.status());
        assertEquals("DUPLICATE_SUCCESS", late.status());
        assertEquals(late.id(), lateRetry.id());
        assertNotEquals(first.id(), late.id());
        assertEquals("PAID", jdbc.orderStatus);
        assertEquals("CLOSED", jdbc.closedStatusObservedBeforeLateCallback);
        assertEquals(1, jdbc.fulfilmentAuditCount);
        assertEquals(1, jdbc.duplicateCaptureAuditCount);
        assertEquals(1, jdbc.automaticRefundCount);
        assertEquals(1, jdbc.refundDispatchCount);
        assertEquals(1, jdbc.safetyRefundAuditCount);
    }

    @Test
    void classificationRequiresBothARealWinnerAndAProgressedOrder() {
        assertEquals(WechatPaymentSettlementService.ExceptionalCaptureKind.DUPLICATE,
                WechatPaymentSettlementService.classifyExceptionalCapture("PAID", true));
        assertEquals(WechatPaymentSettlementService.ExceptionalCaptureKind.DUPLICATE,
                WechatPaymentSettlementService.classifyExceptionalCapture("IN_SERVICE", true));
        assertEquals(WechatPaymentSettlementService.ExceptionalCaptureKind.DUPLICATE,
                WechatPaymentSettlementService.classifyExceptionalCapture("COMPLETED", true));
        assertEquals(WechatPaymentSettlementService.ExceptionalCaptureKind.LATE,
                WechatPaymentSettlementService.classifyExceptionalCapture("CANCELLED", true));
        assertEquals(WechatPaymentSettlementService.ExceptionalCaptureKind.LATE,
                WechatPaymentSettlementService.classifyExceptionalCapture("EXPIRED", false));
        assertEquals(WechatPaymentSettlementService.ExceptionalCaptureKind.LATE,
                WechatPaymentSettlementService.classifyExceptionalCapture("PENDING_PAYMENT", true));
    }

    @Test
    void anotherUsersOrderIsNotExposedAndNeverReachesWechatPay() {
        PrepayJdbc jdbc = new PrepayJdbc();
        RecordingProvider provider = new RecordingProvider();
        WechatJsapiPaymentService service = new WechatJsapiPaymentService(
                jdbc, provider, transactions());
        AuthenticatedPrincipal attacker = new AuthenticatedPrincipal(
                "attacker", "attacker", "USER", List.of("ROLE_USER"),
                "WECHAT", "openid-not-the-owner");

        assertThrows(BusinessException.class,
                () -> service.prepay(attacker, 17L, "attacker-key"));

        assertEquals(0, provider.createCalls.get());
        assertEquals(0, provider.buildCalls.get());
        assertEquals(0, jdbc.insertCount);
    }

    private static PlatformTransactionManager transactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private static ResultSet row(Map<String, Object> values) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.getArgument(0, String.class));
            return value == null ? null : String.valueOf(value);
        });
        when(rs.getLong(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.getArgument(0, String.class));
            return value == null ? 0L : ((Number) value).longValue();
        });
        when(rs.getInt(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.getArgument(0, String.class));
            return value == null ? 0 : ((Number) value).intValue();
        });
        when(rs.getObject(anyString(), org.mockito.ArgumentMatchers.<Class<Object>>any()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0, String.class)));
        return rs;
    }

    private static <T> List<T> map(RowMapper<T> mapper, Map<String, Object> values) {
        try {
            return List.of(mapper.mapRow(row(values), 0));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static <T> T mapOne(RowMapper<T> mapper, Map<String, Object> values) {
        return map(mapper, values).get(0);
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static final class PrepayJdbc extends JdbcTemplate {
        private LocalPayment payment;
        private int insertCount;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            String query = normalized(sql);
            if (query.contains("FROM bf_order o JOIN bf_user u")) {
                if (!"openid-wechat-safety".equals(args[1])) return List.of();
                return map(mapper, Map.of(
                        "id", 17L,
                        "order_no", "ORD-17",
                        "status", "PENDING_PAYMENT",
                        "payable_amount_cent", 188L,
                        "expires_at", LocalDateTime.now().plusMinutes(30),
                        "wechat_openid", "openid-wechat-safety"));
            }
            if (query.contains("WHERE order_id=? AND channel='WECHAT_PAY' AND status='PENDING'")) {
                return payment != null && "PENDING".equals(payment.status())
                        ? map(mapper, payment.values()) : List.of();
            }
            if (query.contains("WHERE payment_no=? AND channel='WECHAT_PAY' FOR UPDATE")) {
                return payment != null && payment.paymentNo().equals(args[0])
                        ? map(mapper, payment.values()) : List.of();
            }
            throw new AssertionError("Unexpected payment query: " + query);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (normalized(sql).contains("SELECT COUNT(*) FROM bf_payment")) {
                return (T) Integer.valueOf(payment != null && "SUCCESS".equals(payment.status()) ? 1 : 0);
            }
            throw new AssertionError("Unexpected payment scalar query: " + normalized(sql));
        }

        @Override
        public int update(String sql, Object... args) {
            String update = normalized(sql);
            if (update.startsWith("INSERT INTO bf_payment")) {
                insertCount++;
                payment = new LocalPayment(
                        String.valueOf(args[1]), "PENDING", ((Number) args[2]).longValue(), null,
                        String.valueOf(args[3]), null, null);
                return 1;
            }
            if (update.startsWith("UPDATE bf_payment") && update.contains("'$.prepayId'")) {
                if (payment == null || !payment.paymentNo().equals(args[1])) return 0;
                payment = payment.withPrepayId(String.valueOf(args[0]));
                return 1;
            }
            throw new AssertionError("Unexpected payment update: " + update);
        }
    }

    private static final class SettlementJdbc extends JdbcTemplate {
        private final Map<String, LocalPayment> payments = new HashMap<>();
        private String orderStatus = "PENDING_PAYMENT";
        private String closedStatusObservedBeforeLateCallback;
        private int fulfilmentAuditCount;
        private int duplicateCaptureAuditCount;
        private int automaticRefundCount;
        private int refundDispatchCount;
        private int safetyRefundAuditCount;

        private SettlementJdbc() {
            payments.put("WX-A", new LocalPayment("WX-A", "PENDING", 188, null,
                    null, null, 101L));
            payments.put("WX-B", new LocalPayment("WX-B", "PENDING", 188, null,
                    null, null, 102L));
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            String query = normalized(sql);
            if (query.contains("FROM bf_payment p JOIN bf_order o")
                    && query.contains("WHERE p.payment_no=?")) {
                LocalPayment payment = payments.get(String.valueOf(args[0]));
                if ("WX-A".equals(payment.paymentNo()) && "CLOSED".equals(payment.status())) {
                    closedStatusObservedBeforeLateCallback = payment.status();
                }
                return map(mapper, settlementValues(payment));
            }
            if (query.contains("FROM bf_payment") && query.contains("status='SUCCESS'")) {
                return payments.values().stream()
                        .filter(payment -> "SUCCESS".equals(payment.status()))
                        .findFirst()
                        .map(payment -> map(mapper, winnerValues(payment)))
                        .orElseGet(List::of);
            }
            if (query.contains("FROM bf_dining_reservation")
                    || query.contains("FROM bf_order_item")) {
                return List.of();
            }
            throw new AssertionError("Unexpected settlement query: " + query);
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
            if (!normalized(sql).contains("WHERE p.id=?")) {
                throw new AssertionError("Unexpected settlement object query: " + normalized(sql));
            }
            long id = ((Number) args[0]).longValue();
            LocalPayment payment = payments.values().stream()
                    .filter(candidate -> candidate.id() == id)
                    .findFirst().orElseThrow();
            return mapOne(mapper, paymentResponseValues(payment));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            String query = normalized(sql);
            if (query.contains("SELECT id FROM bf_refund WHERE automatic_capture_payment_id=?")) {
                return (T) Long.valueOf(9001L);
            }
            throw new AssertionError("Unexpected settlement scalar query: " + query);
        }

        @Override
        public int update(String sql, Object... args) {
            String update = normalized(sql);
            if (update.startsWith("UPDATE bf_payment") && update.contains("status='SUCCESS'")) {
                long id = ((Number) args[2]).longValue();
                replace(id, payment(id).withSettlement("SUCCESS", String.valueOf(args[0])));
                return 1;
            }
            if (update.startsWith("UPDATE bf_order SET status='PAID'")) {
                if (!"PENDING_PAYMENT".equals(orderStatus)) return 0;
                orderStatus = "PAID";
                return 1;
            }
            if (update.startsWith("UPDATE bf_payment") && update.contains("status='CLOSED'")) {
                long winnerId = ((Number) args[2]).longValue();
                payments.replaceAll((number, payment) -> payment.id() == winnerId
                        || !"PENDING".equals(payment.status())
                        ? payment : payment.withStatus("CLOSED"));
                return 1;
            }
            if (update.startsWith("UPDATE bf_payment") && update.contains("status='DUPLICATE_SUCCESS'")) {
                throw new AssertionError("Static duplicate SQL should no longer be used");
            }
            if (update.startsWith("UPDATE bf_payment") && update.contains("SET status=?")) {
                long id = ((Number) args[7]).longValue();
                replace(id, payment(id).withSettlement(String.valueOf(args[0]), String.valueOf(args[1])));
                return 1;
            }
            if (update.startsWith("INSERT INTO bf_refund")) {
                automaticRefundCount++;
                return 1;
            }
            if (update.startsWith("INSERT IGNORE INTO bf_refund_status_log")) return 1;
            if (update.startsWith("INSERT INTO bf_wechat_refund_dispatch")) {
                refundDispatchCount++;
                return 1;
            }
            if (update.startsWith("INSERT INTO bf_audit_log")) {
                String action = args.length > 1 ? String.valueOf(args[1]) : "";
                if ("ORDER_WECHAT_DUPLICATE_PAYMENT_CAPTURED".equals(action)
                        || update.contains("ORDER_WECHAT_DUPLICATE_PAYMENT_CAPTURED")) {
                    duplicateCaptureAuditCount++;
                } else if (update.contains("ORDER_WECHAT_PAY")) {
                    fulfilmentAuditCount++;
                } else if (update.contains("WECHAT_EXCEPTION_REFUND_ENQUEUED")) {
                    safetyRefundAuditCount++;
                }
                return 1;
            }
            // The remaining business projections are intentionally empty in this fixture.
            return 0;
        }

        private LocalPayment payment(long id) {
            return payments.values().stream().filter(candidate -> candidate.id() == id)
                    .findFirst().orElseThrow();
        }

        private void replace(long id, LocalPayment replacement) {
            payments.replaceAll((number, payment) -> payment.id() == id ? replacement : payment);
        }

        private Map<String, Object> settlementValues(LocalPayment payment) {
            Map<String, Object> values = new HashMap<>(payment.values());
            values.put("order_id", 17L);
            values.put("user_id", 7L);
            values.put("order_status", orderStatus);
            return values;
        }

        private Map<String, Object> winnerValues(LocalPayment payment) {
            return Map.of(
                    "id", payment.id(),
                    "payment_no", payment.paymentNo(),
                    "channel", "WECHAT_PAY",
                    "transaction_id", payment.transactionId());
        }

        private Map<String, Object> paymentResponseValues(LocalPayment payment) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", payment.id());
            values.put("order_id", 17L);
            values.put("order_no", "ORD-17");
            values.put("payment_no", payment.paymentNo());
            values.put("channel", "WECHAT_PAY");
            values.put("transaction_id", payment.transactionId());
            values.put("amount_cent", payment.amountCent());
            values.put("status", payment.status());
            values.put("paid_at", LocalDateTime.now());
            return values;
        }
    }

    private record LocalPayment(
            String paymentNo,
            String status,
            long amountCent,
            String prepayId,
            String idempotencyKeyHash,
            String transactionId,
            Long id) {
        private Map<String, Object> values() {
            Map<String, Object> values = new HashMap<>();
            values.put("id", id == null ? 1L : id);
            values.put("payment_no", paymentNo);
            values.put("amount_cent", amountCent);
            values.put("status", status);
            values.put("prepay_id", prepayId);
            values.put("idempotency_key_hash", idempotencyKeyHash);
            values.put("transaction_id", transactionId);
            return values;
        }

        private LocalPayment withPrepayId(String value) {
            return new LocalPayment(paymentNo, status, amountCent, value,
                    idempotencyKeyHash, transactionId, id);
        }

        private LocalPayment withStatus(String value) {
            return new LocalPayment(paymentNo, value, amountCent, prepayId,
                    idempotencyKeyHash, transactionId, id);
        }

        private LocalPayment withSettlement(String value, String channelTransactionId) {
            return new LocalPayment(paymentNo, value, amountCent, prepayId,
                    idempotencyKeyHash, channelTransactionId, id);
        }
    }

    private static final class RecordingProvider implements WechatPayV3Provider {
        private final AtomicInteger createCalls = new AtomicInteger();
        private final AtomicInteger buildCalls = new AtomicInteger();

        @Override
        public Availability availability() {
            return new Availability(true, "test");
        }

        @Override
        public JsapiPrepayResult createJsapiPrepay(JsapiPrepayCommand command) {
            createCalls.incrementAndGet();
            return invocation(command.outTradeNo(), "prepay-" + command.outTradeNo());
        }

        @Override
        public JsapiPrepayResult buildJsapiInvocation(String outTradeNo, String prepayId) {
            buildCalls.incrementAndGet();
            return invocation(outTradeNo, prepayId);
        }

        private JsapiPrepayResult invocation(String outTradeNo, String prepayId) {
            return new JsapiPrepayResult(outTradeNo, "1786500000", "nonce",
                    "prepay_id=" + prepayId, "RSA", "signature");
        }

        @Override
        public RefundResult requestRefund(RefundCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerifiedNotification verifyAndDecrypt(CallbackHeaders headers, String rawBody) {
            throw new UnsupportedOperationException();
        }
    }
}
