package com.iminiapp.backend.payment.application;

import com.iminiapp.backend.payment.api.PaymentResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Applies a cryptographically verified payment to local business state. */
@Service
public class WechatPaymentSettlementService {
    private static final Set<String> CAPTURE_RETRY_STATUSES = Set.of(
            "SUCCESS", "DUPLICATE_SUCCESS", "LATE_SUCCESS",
            "DUPLICATE_REFUNDED", "LATE_REFUNDED",
            "DUPLICATE_REFUND_FAILED", "LATE_REFUND_FAILED",
            "DUPLICATE_REFUND_ABNORMAL", "LATE_REFUND_ABNORMAL");
    private static final Set<String> EXCEPTION_CAPTURE_SOURCE_STATUSES =
            Set.of("PENDING", "CLOSED", "FAILED");
    private static final Set<String> DUPLICATE_ORDER_STATES = Set.of(
            "PAID", "READY", "APPROVED", "WAITING_DRIVER", "ACCEPTED",
            "IN_SERVICE", "PREPARING", "SHIPPED", "CHECKED_IN", "CONFIRMED",
            "COMPLETED", "REFUNDING", "REFUNDED");

    private final JdbcTemplate jdbcTemplate;
    private final VerificationTicketService verificationTicketService;
    private final WechatRefundDispatchService refundDispatchService;

    public WechatPaymentSettlementService(
            JdbcTemplate jdbcTemplate,
            VerificationTicketService verificationTicketService,
            WechatRefundDispatchService refundDispatchService) {
        this.jdbcTemplate = jdbcTemplate;
        this.verificationTicketService = verificationTicketService;
        this.refundDispatchService = refundDispatchService;
    }

    @Transactional
    public PaymentResponse settle(
            String outTradeNo,
            String transactionId,
            long amountCent,
            String notificationId) {
        List<PendingPayment> rows = jdbcTemplate.query("""
                SELECT p.id,p.order_id,p.vehicle_settlement_id,p.payment_no,p.status,p.transaction_id,p.amount_cent,
                       o.user_id,o.status order_status
                FROM bf_payment p JOIN bf_order o ON o.id=p.order_id
                WHERE p.payment_no=? AND p.channel='WECHAT_PAY' FOR UPDATE
                """, (rs, n) -> new PendingPayment(
                rs.getLong("id"), rs.getLong("order_id"),
                rs.getObject("vehicle_settlement_id", Long.class), rs.getString("payment_no"),
                rs.getString("status"),
                rs.getString("transaction_id"), rs.getLong("amount_cent"),
                rs.getLong("user_id"), rs.getString("order_status")), outTradeNo);
        if (rows.isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "Unknown WeChat Pay merchant order number");
        }
        PendingPayment payment = rows.get(0);
        if (payment.amountCent() != amountCent) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "WeChat Pay callback amount does not match the local payment");
        }
        if (CAPTURE_RETRY_STATUSES.contains(payment.status())) {
            if (!transactionId.equals(payment.transactionId())) {
                throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                        "WeChat Pay transaction id does not match the settled payment");
            }
            return findPayment(payment.id());
        }
        if (payment.vehicleSettlementId() != null) {
            return settleVehicleSupplement(payment, transactionId, amountCent, notificationId);
        }
        if (!("PENDING_PAYMENT".equals(payment.orderStatus()) && "PENDING".equals(payment.status()))) {
            if (EXCEPTION_CAPTURE_SOURCE_STATUSES.contains(payment.status())) {
                return recordExceptionalCapture(payment, transactionId, amountCent, notificationId);
            }
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Local payment state does not allow automatic settlement or safety refund");
        }
        if (jdbcTemplate.update("""
                UPDATE bf_payment
                SET status='SUCCESS',transaction_id=?,paid_at=CURRENT_TIMESTAMP,
                    callback_payload=JSON_OBJECT('source','WECHAT_PAY_V3_CALLBACK',
                        'verified',TRUE,'notificationId',?)
                WHERE id=? AND status='PENDING'
                """, transactionId, notificationId, payment.id()) != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "WeChat Pay transaction was concurrently settled");
        }
        if (jdbcTemplate.update("""
                UPDATE bf_order SET status='PAID',paid_amount_cent=payable_amount_cent
                WHERE id=? AND status='PENDING_PAYMENT'
                """, payment.orderId()) != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Order state changed while settling the WeChat Pay transaction");
        }
        int closedPending = jdbcTemplate.update("""
                UPDATE bf_payment
                SET status='CLOSED',
                    callback_payload=JSON_SET(COALESCE(callback_payload,JSON_OBJECT()),
                        '$.closeReason','ANOTHER_WECHAT_PAYMENT_SUCCEEDED',
                        '$.closedByPaymentNo',?)
                WHERE order_id=? AND channel='WECHAT_PAY' AND id<>? AND status='PENDING'
                """, payment.paymentNo(), payment.orderId(), payment.id());
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
                    JSON_OBJECT('notificationId',?,'transactionId',?,'amountCent',?,
                        'closedPendingCount',?))
                """, payment.userId(), String.valueOf(payment.orderId()), notificationId,
                transactionId, amountCent, closedPending);
        confirmSportBooking(payment.orderId(), payment.userId(), outTradeNo);
        confirmMeetingBooking(payment.orderId(), payment.userId(), outTradeNo);
        confirmDiningReservation(payment.orderId(), payment.userId(), outTradeNo);
        finalizeCulturalStock(payment.orderId());
        verificationTicketService.issueOrderTickets(payment.orderId());
        return findPayment(payment.id());
    }

    private PaymentResponse settleVehicleSupplement(PendingPayment payment, String transactionId,
            long amountCent, String notificationId) {
        List<VehicleSupplement> rows = jdbcTemplate.query("""
                SELECT s.id,s.status,s.supplement_amount_cent,s.supplement_paid_cent,o.status order_status
                FROM bf_vehicle_settlement s JOIN bf_order o ON o.id=s.order_id
                WHERE s.id=? AND s.order_id=? FOR UPDATE
                """, (rs, n) -> new VehicleSupplement(rs.getLong("id"), rs.getString("status"),
                rs.getLong("supplement_amount_cent"), rs.getLong("supplement_paid_cent"),
                rs.getString("order_status")), payment.vehicleSettlementId(), payment.orderId());
        if (rows.isEmpty()) {
            return recordExceptionalCapture(payment, transactionId, amountCent, notificationId);
        }
        VehicleSupplement settlement = rows.get(0);
        long outstanding = settlement.amountCent() - settlement.paidCent();
        if (!"PENDING".equals(payment.status()) || !"PENDING_SUPPLEMENT".equals(settlement.status())
                || !"PENDING_SUPPLEMENT".equals(settlement.orderStatus()) || outstanding != amountCent) {
            return recordExceptionalCapture(payment, transactionId, amountCent, notificationId);
        }
        if (jdbcTemplate.update("""
                UPDATE bf_payment SET status='SUCCESS',transaction_id=?,paid_at=CURRENT_TIMESTAMP,
                    callback_payload=JSON_SET(COALESCE(callback_payload,JSON_OBJECT()),
                        '$.source','WECHAT_PAY_V3_CALLBACK','$.verified',TRUE,'$.notificationId',?)
                WHERE id=? AND status='PENDING'
                """, transactionId, notificationId, payment.id()) != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆补款支付状态已发生变化");
        }
        if (jdbcTemplate.update("""
                UPDATE bf_vehicle_settlement SET supplement_paid_cent=supplement_amount_cent,
                    status='SETTLED',settled_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='PENDING_SUPPLEMENT'
                  AND supplement_amount_cent-supplement_paid_cent=?
                """, settlement.id(), amountCent) != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆补款结算状态已发生变化");
        }
        if (jdbcTemplate.update("""
                UPDATE bf_order SET status='COMPLETED',paid_amount_cent=paid_amount_cent+?,
                    completed_at=COALESCE(completed_at,CURRENT_TIMESTAMP)
                WHERE id=? AND status='PENDING_SUPPLEMENT'
                """, amountCent, payment.orderId()) != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT, "车辆订单状态已发生变化");
        }
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,'VEHICLE_SUPPLEMENT_WECHAT_PAY','VEHICLE_SETTLEMENT',?,JSON_OBJECT(
                    'orderId',?,'paymentId',?,'transactionId',?,'notificationId',?,'amountCent',?))
                """, payment.userId(), String.valueOf(settlement.id()), payment.orderId(), payment.id(),
                transactionId, notificationId, amountCent);
        return findPayment(payment.id());
    }

    /** Records a verified but non-fulfillable channel capture and atomically queues its refund. */
    private PaymentResponse recordExceptionalCapture(
            PendingPayment payment,
            String transactionId,
            long amountCent,
            String notificationId) {
        List<WinningPayment> winners = jdbcTemplate.query("""
                SELECT id,payment_no,channel,transaction_id
                FROM bf_payment
                WHERE order_id=? AND id<>? AND status='SUCCESS'
                ORDER BY id LIMIT 1 FOR UPDATE
                """, (rs, n) -> new WinningPayment(
                rs.getLong("id"), rs.getString("payment_no"), rs.getString("channel"),
                rs.getString("transaction_id")), payment.orderId(), payment.id());
        WinningPayment winner = winners.isEmpty() ? null : winners.get(0);
        ExceptionalCaptureKind kind = classifyExceptionalCapture(payment.orderStatus(), winner != null);
        String paymentStatus = kind == ExceptionalCaptureKind.DUPLICATE
                ? "DUPLICATE_SUCCESS" : "LATE_SUCCESS";
        String refundKind = kind == ExceptionalCaptureKind.DUPLICATE
                ? "AUTO_DUPLICATE_CAPTURE" : "AUTO_LATE_CAPTURE";
        String refundNo = (kind == ExceptionalCaptureKind.DUPLICATE ? "WXAUTOD" : "WXAUTOL")
                + payment.id();
        String reason = kind == ExceptionalCaptureKind.DUPLICATE
                ? "自动退回重复扣取的微信支付款项"
                : "自动退回订单不可履约后扣取的微信支付款项";
        int changed = jdbcTemplate.update("""
                UPDATE bf_payment
                SET status=?,transaction_id=?,paid_at=CURRENT_TIMESTAMP,
                    callback_payload=JSON_SET(COALESCE(callback_payload,JSON_OBJECT()),
                        '$.source','WECHAT_PAY_V3_CALLBACK','$.verified',TRUE,
                        '$.notificationId',?,'$.exceptionalCapture',TRUE,
                        '$.exceptionKind',?,'$.orderStatusAtCapture',?,
                        '$.winningPaymentId',?,'$.winningPaymentNo',?)
                WHERE id=? AND status IN ('PENDING','CLOSED','FAILED')
                """, paymentStatus, transactionId, notificationId, refundKind,
                payment.orderStatus(), winner == null ? null : winner.id(),
                winner == null ? null : winner.paymentNo(), payment.id());
        if (changed != 1) {
            throw new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,
                    "Exceptional WeChat Pay capture was concurrently reconciled");
        }

        jdbcTemplate.update("""
                INSERT INTO bf_refund
                (refund_no,order_id,payment_id,automatic_capture_payment_id,amount_cent,reason,
                 refund_kind,status,requested_by,idempotency_key,previous_order_status)
                VALUES (?,?,?,?,?, ?,?,'PROCESSING',? ,?,?)
                ON DUPLICATE KEY UPDATE automatic_capture_payment_id=VALUES(automatic_capture_payment_id)
                """, refundNo, payment.orderId(), payment.id(), payment.id(), amountCent,
                reason, refundKind, payment.userId(), "WECHAT-AUTO-REFUND:" + payment.id(),
                payment.orderStatus());
        Long refundId = jdbcTemplate.queryForObject("""
                SELECT id FROM bf_refund WHERE automatic_capture_payment_id=?
                """, Long.class, payment.id());
        if (refundId == null) {
            throw new BusinessException(CommonErrorCode.REFUND_CONFLICT,
                    "Automatic refund record could not be persisted");
        }
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_refund_status_log
                (refund_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,NULL,'PROCESSING',?,?,?)
                """, refundId, payment.userId(), reason, "WECHAT-AUTO-REFUND:" + payment.id());
        refundDispatchService.enqueueAutomatic(refundId,
                new WechatPayV3Provider.RefundCommand(
                        refundNo, payment.paymentNo(), transactionId, amountCent, amountCent,
                        "CNY", reason));

        String auditAction = kind == ExceptionalCaptureKind.DUPLICATE
                ? "ORDER_WECHAT_DUPLICATE_PAYMENT_CAPTURED"
                : "ORDER_WECHAT_LATE_PAYMENT_CAPTURED";
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?,'PAYMENT',?,
                    JSON_OBJECT('orderId',?,'paymentNo',?,'transactionId',?,
                        'amountCent',?,'notificationId',?,'winningPaymentId',?,
                        'winningPaymentNo',?,'winningChannel',?,'refundId',?,
                        'refundNo',?,'orderStatusAtCapture',?))
                """, payment.userId(), auditAction, String.valueOf(payment.id()), payment.orderId(),
                payment.paymentNo(), transactionId, amountCent, notificationId,
                winner == null ? null : winner.id(), winner == null ? null : winner.paymentNo(),
                winner == null ? null : winner.channel(), refundId, refundNo, payment.orderStatus());
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,'WECHAT_EXCEPTION_REFUND_ENQUEUED','REFUND',?,
                    JSON_OBJECT('orderId',?,'paymentId',?,'paymentStatus',?,
                        'refundKind',?,'amountCent',?))
                """, payment.userId(), String.valueOf(refundId), payment.orderId(), payment.id(),
                paymentStatus, refundKind, amountCent);
        return findPayment(payment.id());
    }

    static ExceptionalCaptureKind classifyExceptionalCapture(String orderStatus, boolean hasWinner) {
        return hasWinner && DUPLICATE_ORDER_STATES.contains(orderStatus)
                ? ExceptionalCaptureKind.DUPLICATE
                : ExceptionalCaptureKind.LATE;
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
            if (changed != 1) {
                throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,
                        "Cultural product locked stock is inconsistent");
            }
            jdbcTemplate.update("""
                    INSERT INTO bf_cultural_stock_log
                    (sku_id,change_type,quantity_delta,on_hand_after,available_after,idempotency_key,reference_no,remark)
                    SELECT ?, 'SALE_DEDUCT', ?, on_hand_quantity, available_quantity, ?,
                           (SELECT order_no FROM bf_order WHERE id=?), 'WECHAT_PAY_SETTLEMENT'
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
                SELECT id,'HELD','CONFIRMED',?,'WECHAT_PAY_SETTLEMENT',CONCAT('SPORT-PAY:',id)
                FROM bf_sport_booking WHERE order_id=?
                """, operatorId, orderId);
    }

    private void confirmMeetingBooking(long orderId, long operatorId, String paymentNo) {
        jdbcTemplate.update("""
                UPDATE bf_meeting_reservation SET status='PAID'
                WHERE order_id=? AND status='PENDING_PAYMENT'
                """, orderId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_meeting_reservation_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                SELECT id,'PENDING_PAYMENT','PAID',?,'WECHAT_PAY_SETTLEMENT',CONCAT('MEETING-PAY:',id)
                FROM bf_meeting_reservation WHERE order_id=? AND status='PAID'
                """, operatorId, orderId);
    }

    private void confirmDiningReservation(long orderId, long operatorId, String paymentNo) {
        List<DiningReservation> reservations = jdbcTemplate.query("""
                SELECT id,reservation_no FROM bf_dining_reservation
                WHERE order_id=? AND status='PENDING_PAYMENT' FOR UPDATE
                """, (rs, n) -> new DiningReservation(rs.getLong(1), rs.getString(2)), orderId);
        for (DiningReservation reservation : reservations) {
            List<DiningComponent> components = jdbcTemplate.query("""
                    SELECT x.dish_id,SUM(x.qty) qty FROM (
                      SELECT i.dish_id,i.quantity qty FROM bf_dining_reservation_item i
                      WHERE i.reservation_id=? AND i.item_type='DISH'
                      UNION ALL
                      SELECT pi.dish_id,pi.quantity*i.quantity qty FROM bf_dining_reservation_item i
                      JOIN bf_dining_package_item pi ON pi.package_id=i.package_id
                      WHERE i.reservation_id=? AND i.item_type='PACKAGE') x GROUP BY x.dish_id
                    """, (rs, n) -> new DiningComponent(rs.getLong(1), rs.getInt(2)),
                    reservation.id(), reservation.id());
            for (DiningComponent component : components) {
                if (jdbcTemplate.update("""
                        UPDATE bf_dining_stock
                        SET on_hand_quantity=on_hand_quantity-?,locked_quantity=locked_quantity-?,version=version+1
                        WHERE dish_id=? AND on_hand_quantity>=? AND locked_quantity>=?
                        """, component.quantity(), component.quantity(), component.dishId(),
                        component.quantity(), component.quantity()) != 1) {
                    throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,
                            "Dining locked stock is inconsistent");
                }
                jdbcTemplate.update("""
                        INSERT IGNORE INTO bf_dining_stock_log
                        (dish_id,change_type,quantity_delta,on_hand_after,locked_after,idempotency_key,reference_no,remark)
                        SELECT ?,'SALE_DEDUCT',?,on_hand_quantity,locked_quantity,?,?, 'WECHAT_PAY_SETTLEMENT'
                        FROM bf_dining_stock WHERE dish_id=?
                        """, component.dishId(), -component.quantity(),
                        "DINING-PAY:" + reservation.id() + ":" + component.dishId(),
                        reservation.number(), component.dishId());
            }
            jdbcTemplate.update("UPDATE bf_dining_reservation SET status='PAID' WHERE id=?", reservation.id());
            jdbcTemplate.update("""
                    INSERT IGNORE INTO bf_dining_kitchen_ticket(reservation_id,station,status)
                    VALUES (?,'KITCHEN','NEW'),(?,'FRONT_DESK','NEW')
                    """, reservation.id(), reservation.id());
            jdbcTemplate.update("""
                    INSERT IGNORE INTO bf_dining_status_log
                    (reservation_id,from_status,to_status,operator_id,note,idempotency_key)
                    VALUES (?,'PENDING_PAYMENT','PAID',?,'WECHAT_PAY_SETTLEMENT',?)
                    """, reservation.id(), operatorId, "DINING-PAY:" + reservation.id());
        }
    }

    private PaymentResponse findPayment(long paymentId) {
        return jdbcTemplate.queryForObject("""
                SELECT p.id,p.order_id,o.order_no,p.payment_no,p.channel,p.transaction_id,
                       p.amount_cent,p.status,p.paid_at
                FROM bf_payment p JOIN bf_order o ON o.id=p.order_id WHERE p.id=?
                """, (rs, n) -> new PaymentResponse(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no"),
                rs.getString("payment_no"), rs.getString("channel"), rs.getString("transaction_id"),
                rs.getLong("amount_cent"), "CNY", rs.getString("status"),
                rs.getObject("paid_at", LocalDateTime.class)), paymentId);
    }

    private record PendingPayment(long id, long orderId, Long vehicleSettlementId,
                                  String paymentNo, String status,
                                  String transactionId, long amountCent, long userId,
                                  String orderStatus) {
    }

    private record VehicleSupplement(long id, String status, long amountCent,
                                     long paidCent, String orderStatus) {}

    private record WinningPayment(
            long id,
            String paymentNo,
            String channel,
            String transactionId) {
    }

    enum ExceptionalCaptureKind {
        DUPLICATE,
        LATE
    }

    private record CulturalItem(long orderItemId, long skuId, int quantity) {
    }

    private record DiningReservation(long id, String number) {
    }

    private record DiningComponent(long dishId, int quantity) {
    }
}
