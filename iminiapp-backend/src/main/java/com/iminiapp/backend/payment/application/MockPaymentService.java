package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.domain.OrderStatePolicy;
import com.iminiapp.backend.payment.api.PaymentResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Profile({"local", "test"})
public class MockPaymentService {
    private static final DateTimeFormatter PAYMENT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final JdbcTemplate jdbcTemplate;
    private final VerificationTicketService verificationTicketService;
    private final PaymentGateway paymentGateway;

    public MockPaymentService(
            JdbcTemplate jdbcTemplate,
            VerificationTicketService verificationTicketService,
            PaymentGateway paymentGateway) {
        this.jdbcTemplate = jdbcTemplate;
        this.verificationTicketService = verificationTicketService;
        this.paymentGateway = paymentGateway;
    }

    /**
     * Completes an order only after the API v3 callback has been verified and decrypted.
     * The callback event table provides request-level idempotency; payment and stock
     * updates below remain guarded as a second line of defence.
     */
    @Transactional
    public PaymentResponse settleVerifiedWechatPayment(
            String outTradeNo,
            String transactionId,
            long amountCent,
            String notificationId) {
        List<WechatPendingPayment> rows = jdbcTemplate.query("""
                SELECT p.id,p.order_id,p.status,p.transaction_id,p.amount_cent,
                       o.user_id,o.status order_status
                FROM bf_payment p JOIN bf_order o ON o.id=p.order_id
                WHERE p.payment_no=? AND p.channel='WECHAT_PAY' FOR UPDATE
                """, (rs, n) -> new WechatPendingPayment(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("status"),
                rs.getString("transaction_id"), rs.getLong("amount_cent"),
                rs.getLong("user_id"), rs.getString("order_status")), outTradeNo);
        if (rows.isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "Unknown WeChat Pay merchant order number");
        }
        WechatPendingPayment payment = rows.get(0);
        if (payment.amountCent() != amountCent) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "WeChat Pay callback amount does not match the local payment");
        }
        if ("SUCCESS".equals(payment.status())) {
            if (!transactionId.equals(payment.transactionId())) {
                throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                        "WeChat Pay transaction id does not match the settled payment");
            }
            return findPayment(payment.id());
        }
        if (!"PENDING".equals(payment.status()) || !"PENDING_PAYMENT".equals(payment.orderStatus())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Local order or payment state does not allow automatic settlement");
        }
        int paymentChanged = jdbcTemplate.update("""
                UPDATE bf_payment
                SET status='SUCCESS',transaction_id=?,paid_at=CURRENT_TIMESTAMP,
                    callback_payload=JSON_OBJECT('source','WECHAT_PAY_V3_CALLBACK',
                        'verified',TRUE,'notificationId',?)
                WHERE id=? AND status='PENDING'
                """, transactionId, notificationId, payment.id());
        if (paymentChanged != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "WeChat Pay transaction was concurrently settled");
        }
        int orderChanged = jdbcTemplate.update("""
                UPDATE bf_order SET status='PAID',paid_amount_cent=payable_amount_cent
                WHERE id=? AND status='PENDING_PAYMENT'
                """, payment.orderId());
        if (orderChanged != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Order state changed while settling the WeChat Pay transaction");
        }
        jdbcTemplate.update("""
                INSERT INTO bf_vehicle_deposit(order_id,deposit_amount_cent,status,held_at)
                SELECT o.id,b.deposit_amount_cent,'HELD',CURRENT_TIMESTAMP
                FROM bf_order o JOIN bf_order_item i ON i.order_id=o.id
                JOIN bf_vehicle_booking b ON b.order_item_id=i.id
                WHERE o.id=? AND o.order_type='VEHICLE_RENTAL' AND b.deposit_amount_cent>0
                ON DUPLICATE KEY UPDATE deposit_amount_cent=VALUES(deposit_amount_cent),status='HELD'
                """, payment.orderId());
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,'ORDER_WECHAT_PAY','ORDER',?,
                    JSON_OBJECT('notificationId',?,'transactionId',?,'amountCent',?))
                """, payment.userId(), String.valueOf(payment.orderId()), notificationId,
                transactionId, amountCent);
        confirmSportBooking(payment.orderId(), payment.userId(), outTradeNo);
        confirmMeetingBooking(payment.orderId(), payment.userId(), outTradeNo);
        confirmDiningReservation(payment.orderId(), payment.userId(), outTradeNo);
        finalizeCulturalStock(payment.orderId());
        verificationTicketService.issueOrderTickets(payment.orderId());
        return findPayment(payment.id());
    }

    @Transactional
    public PaymentResponse pay(AuthenticatedPrincipal principal, long orderId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        long userId = requireDatabaseUser(principal);
        PaymentOrder order = lockOwnedOrder(userId, orderId);
        PaymentResponse completed = findSuccessfulPayment(orderId);
        if (completed != null) {
            confirmSportBooking(orderId, userId, completed.paymentNo());
            confirmMeetingBooking(orderId, userId, completed.paymentNo());
            confirmDiningReservation(orderId, userId, completed.paymentNo());
            finalizeCulturalStock(orderId);
            verificationTicketService.issueOrderTickets(orderId);
            return completed;
        }
        if (!OrderStatePolicy.canPay(order.status())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "只有待支付订单可以支付，当前状态为 " + order.status());
        }
        if (order.expiresAt() != null && order.expiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "订单已超过支付截止时间");
        }
        PaymentGateway.PaymentExecution execution = paymentGateway.execute(
                new PaymentGateway.PaymentCommand(orderId, order.payableAmountCent(), "CNY", idempotencyKey));
        PaymentResponse duplicate = findByTransactionId(execution.transactionId());
        if (duplicate != null) return duplicate;

        String paymentNo = nextPaymentNo();
        long paymentId = insertPayment(order, paymentNo, execution);
        int changed = jdbcTemplate.update("""
                UPDATE bf_order SET status = 'PAID', paid_amount_cent = payable_amount_cent
                WHERE id = ? AND user_id = ? AND status = 'PENDING_PAYMENT'
                """, orderId, userId);
        if (changed != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "订单状态已变化，请刷新后重试");
        }
        jdbcTemplate.update("""
                INSERT INTO bf_vehicle_deposit(order_id,deposit_amount_cent,status,held_at)
                SELECT o.id,b.deposit_amount_cent,'HELD',CURRENT_TIMESTAMP
                FROM bf_order o JOIN bf_order_item i ON i.order_id=o.id
                JOIN bf_vehicle_booking b ON b.order_item_id=i.id
                WHERE o.id=? AND o.order_type='VEHICLE_RENTAL' AND b.deposit_amount_cent>0
                ON DUPLICATE KEY UPDATE deposit_amount_cent=VALUES(deposit_amount_cent),status='HELD'
                """, orderId);
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log (operator_id, action, target_type, target_id, detail)
                VALUES (?, 'ORDER_MOCK_PAY', 'ORDER', ?, JSON_OBJECT('paymentNo', ?, 'amountCent', ?))
                """, userId, String.valueOf(orderId), paymentNo, order.payableAmountCent());
        confirmSportBooking(orderId, userId, paymentNo);
        confirmMeetingBooking(orderId, userId, paymentNo);
        confirmDiningReservation(orderId, userId, paymentNo);
        finalizeCulturalStock(orderId);
        verificationTicketService.issueOrderTickets(orderId);
        return findPayment(paymentId);
    }

    private void finalizeCulturalStock(long orderId) {
        List<CulturalItem> items = jdbcTemplate.query("""
                SELECT id,sku_id,quantity FROM bf_order_item
                WHERE order_id=? AND resource_type='CULTURAL_PRODUCT' ORDER BY id
                """, (rs, n) -> new CulturalItem(rs.getLong("id"), rs.getLong("sku_id"), rs.getInt("quantity")),
                orderId);
        for (CulturalItem item : items) {
            String key = "PAYMENT-DEDUCT:" + item.orderItemId();
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bf_cultural_stock_log WHERE idempotency_key=?", Integer.class, key);
            if (exists != null && exists > 0) continue;
            int changed = jdbcTemplate.update("""
                    UPDATE bf_cultural_stock
                    SET on_hand_quantity=on_hand_quantity-?,locked_quantity=locked_quantity-?,version=version+1
                    WHERE sku_id=? AND locked_quantity>=? AND on_hand_quantity>=?
                    """, item.quantity(), item.quantity(), item.skuId(), item.quantity(), item.quantity());
            if (changed != 1) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT, "文创商品锁定库存状态不一致");
            jdbcTemplate.update("""
                    INSERT INTO bf_cultural_stock_log
                    (sku_id,change_type,quantity_delta,on_hand_after,available_after,idempotency_key,reference_no,remark)
                    SELECT ?, 'SALE_DEDUCT', ?, on_hand_quantity, available_quantity, ?,
                           (SELECT order_no FROM bf_order WHERE id=?), '支付成功扣减实体库存'
                    FROM bf_cultural_stock WHERE sku_id=?
                    """, item.skuId(), -item.quantity(), key, orderId, item.skuId());
        }
    }

    private void confirmSportBooking(long orderId, long operatorId, String paymentNo) {
        jdbcTemplate.update("""
                UPDATE bf_sport_booking SET status='CONFIRMED',confirmed_at=COALESCE(confirmed_at,CURRENT_TIMESTAMP)
                WHERE order_id=? AND status='HELD'
                """, orderId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_sport_booking_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                SELECT id,'HELD','CONFIRMED',?,CONCAT('模拟支付成功：',?),CONCAT('SPORT-PAY:',id)
                FROM bf_sport_booking WHERE order_id=?
                """, operatorId, paymentNo, orderId);
    }

    private void confirmMeetingBooking(long orderId, long operatorId, String paymentNo) {
        jdbcTemplate.update("""
                UPDATE bf_meeting_reservation SET status='PAID'
                WHERE order_id=? AND status='PENDING_PAYMENT'
                """, orderId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_meeting_reservation_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                SELECT id,'PENDING_PAYMENT','PAID',?,CONCAT('模拟支付成功：',?),CONCAT('MEETING-PAY:',id)
                FROM bf_meeting_reservation WHERE order_id=? AND status='PAID'
                """, operatorId, paymentNo, orderId);
    }

    private void confirmDiningReservation(long orderId, long operatorId, String paymentNo) {
        List<DiningReservation> reservations = jdbcTemplate.query("""
                SELECT id,reservation_no FROM bf_dining_reservation
                WHERE order_id=? AND status='PENDING_PAYMENT' FOR UPDATE
                """, (rs,n)->new DiningReservation(rs.getLong(1),rs.getString(2)), orderId);
        for (DiningReservation reservation : reservations) {
            List<DiningComponent> components = jdbcTemplate.query("""
                    SELECT x.dish_id,SUM(x.qty) qty FROM (
                      SELECT i.dish_id,i.quantity qty FROM bf_dining_reservation_item i
                      WHERE i.reservation_id=? AND i.item_type='DISH'
                      UNION ALL
                      SELECT pi.dish_id,pi.quantity*i.quantity qty FROM bf_dining_reservation_item i
                      JOIN bf_dining_package_item pi ON pi.package_id=i.package_id
                      WHERE i.reservation_id=? AND i.item_type='PACKAGE') x GROUP BY x.dish_id
                    """, (rs,n)->new DiningComponent(rs.getLong(1),rs.getInt(2)), reservation.id(), reservation.id());
            for (DiningComponent c : components) {
                int changed = jdbcTemplate.update("""
                        UPDATE bf_dining_stock SET on_hand_quantity=on_hand_quantity-?,locked_quantity=locked_quantity-?,version=version+1
                        WHERE dish_id=? AND on_hand_quantity>=? AND locked_quantity>=?
                        """, c.quantity(),c.quantity(),c.dishId(),c.quantity(),c.quantity());
                if(changed!=1) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,"餐饮锁定库存状态不一致");
                jdbcTemplate.update("""
                        INSERT IGNORE INTO bf_dining_stock_log
                        (dish_id,change_type,quantity_delta,on_hand_after,locked_after,idempotency_key,reference_no,remark)
                        SELECT ?,'SALE_DEDUCT',?,on_hand_quantity,locked_quantity,?,?, '支付成功扣减菜品库存'
                        FROM bf_dining_stock WHERE dish_id=?
                        """,c.dishId(),-c.quantity(),"DINING-PAY:"+reservation.id()+":"+c.dishId(),reservation.no(),c.dishId());
            }
            jdbcTemplate.update("UPDATE bf_dining_reservation SET status='PAID' WHERE id=?",reservation.id());
            jdbcTemplate.update("""
                    INSERT IGNORE INTO bf_dining_kitchen_ticket(reservation_id,station,status)
                    VALUES (?,'KITCHEN','NEW'),(?,'FRONT_DESK','NEW')
                    """,reservation.id(),reservation.id());
            jdbcTemplate.update("""
                    INSERT IGNORE INTO bf_dining_status_log(reservation_id,from_status,to_status,operator_id,note,idempotency_key)
                    VALUES (?,'PENDING_PAYMENT','PAID',?,CONCAT('模拟支付成功：',?),?)
                    """,reservation.id(),operatorId,paymentNo,"DINING-PAY:"+reservation.id());
        }
    }

    private long insertPayment(
            PaymentOrder order,
            String paymentNo,
            PaymentGateway.PaymentExecution execution) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_payment
                    (order_id, payment_no, channel, transaction_id, amount_cent, status, paid_at, callback_payload)
                    VALUES (?, ?, ?, ?, ?, 'SUCCESS', CURRENT_TIMESTAMP,
                            JSON_OBJECT('source', ?, 'verified', ?))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, order.id());
            statement.setString(2, paymentNo);
            statement.setString(3, execution.channel());
            statement.setString(4, execution.transactionId());
            statement.setLong(5, order.payableAmountCent());
            statement.setString(6, execution.source());
            statement.setBoolean(7, execution.verified());
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private PaymentOrder lockOwnedOrder(long userId, long orderId) {
        List<PaymentOrder> rows = jdbcTemplate.query("""
                SELECT id, status, payable_amount_cent, expires_at
                FROM bf_order WHERE id = ? AND user_id = ? FOR UPDATE
                """, (rs, n) -> new PaymentOrder(rs.getLong("id"), rs.getString("status"),
                rs.getLong("payable_amount_cent"), rs.getObject("expires_at", LocalDateTime.class)), orderId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "订单不存在或无权访问");
        return rows.get(0);
    }

    private PaymentResponse findSuccessfulPayment(long orderId) {
        List<PaymentResponse> rows = jdbcTemplate.query("""
                SELECT p.id, p.order_id, o.order_no, p.payment_no, p.channel, p.transaction_id,
                       p.amount_cent, p.status, p.paid_at
                FROM bf_payment p JOIN bf_order o ON o.id = p.order_id
                WHERE p.order_id = ? AND p.status = 'SUCCESS' ORDER BY p.id LIMIT 1
                """, paymentMapper(), orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PaymentResponse findByTransactionId(String transactionId) {
        List<PaymentResponse> rows = jdbcTemplate.query("""
                SELECT p.id, p.order_id, o.order_no, p.payment_no, p.channel, p.transaction_id,
                       p.amount_cent, p.status, p.paid_at
                FROM bf_payment p JOIN bf_order o ON o.id = p.order_id WHERE p.transaction_id = ?
                """, paymentMapper(), transactionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PaymentResponse findPayment(long paymentId) {
        return jdbcTemplate.queryForObject("""
                SELECT p.id, p.order_id, o.order_no, p.payment_no, p.channel, p.transaction_id,
                       p.amount_cent, p.status, p.paid_at
                FROM bf_payment p JOIN bf_order o ON o.id = p.order_id WHERE p.id = ?
                """, paymentMapper(), paymentId);
    }

    private RowMapper<PaymentResponse> paymentMapper() {
        return (rs, n) -> new PaymentResponse(rs.getLong("id"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getString("payment_no"), rs.getString("channel"),
                rs.getString("transaction_id"), rs.getLong("amount_cent"), "CNY",
                rs.getString("status"), rs.getObject("paid_at", LocalDateTime.class));
    }

    private long requireDatabaseUser(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid = ?",
                Long.class, principal.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户尚未创建订单记录");
        return ids.get(0);
    }

    private void validateIdempotencyKey(String key) {
        if (!StringUtils.hasText(key) || key.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Idempotency-Key 不能为空且最长64字符");
        }
    }

    private String nextPaymentNo() {
        return "PAY" + LocalDateTime.now().format(PAYMENT_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private record PaymentOrder(long id, String status, long payableAmountCent, LocalDateTime expiresAt) {}
    private record CulturalItem(long orderItemId, long skuId, int quantity) {}
    private record DiningReservation(long id,String no) {}
    private record DiningComponent(long dishId,int quantity) {}
    private record WechatPendingPayment(long id, long orderId, String status, String transactionId,
                                        long amountCent, long userId, String orderStatus) {}
}
