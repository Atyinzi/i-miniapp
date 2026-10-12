package com.iminiapp.backend.refund.application;

import com.iminiapp.backend.cultural.application.CulturalAfterSaleRefundService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.domain.OrderStatePolicy;
import com.iminiapp.backend.payment.application.WechatPayV3Provider;
import com.iminiapp.backend.payment.application.WechatRefundDispatchService;
import com.iminiapp.backend.payment.application.WechatExceptionalCaptureRefundService;
import com.iminiapp.backend.refund.api.RefundApplicationResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class RefundApplicationService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final JdbcTemplate jdbcTemplate;
    private final WechatRefundDispatchService wechatRefundDispatchService;
    private final WechatExceptionalCaptureRefundService exceptionalCaptureRefundService;
    private final CulturalAfterSaleRefundService culturalAfterSaleRefundService;
    public RefundApplicationService(JdbcTemplate jdbcTemplate,
            WechatRefundDispatchService wechatRefundDispatchService,
            WechatExceptionalCaptureRefundService exceptionalCaptureRefundService,
            CulturalAfterSaleRefundService culturalAfterSaleRefundService) {
        this.jdbcTemplate = jdbcTemplate;
        this.wechatRefundDispatchService = wechatRefundDispatchService;
        this.exceptionalCaptureRefundService = exceptionalCaptureRefundService;
        this.culturalAfterSaleRefundService = culturalAfterSaleRefundService;
    }

    @Transactional
    public RefundApplicationResponse apply(AuthenticatedPrincipal principal, long orderId, String key, String reason) {
        validatePage(1, 1);
        if (!StringUtils.hasText(key) || key.length() > 64) throw invalid("Idempotency-Key不能为空且最长64字符");
        long userId = requireUser(principal);
        List<RefundApplicationResponse> duplicate = jdbcTemplate.query(select() + " WHERE r.requested_by=? AND r.idempotency_key=?", mapper(), userId, key);
        if (!duplicate.isEmpty()) {
            if (duplicate.get(0).orderId() != orderId) throw conflict("该幂等键已用于另一订单");
            return duplicate.get(0);
        }
        Order order = lockOrder(orderId, userId);
        duplicate = jdbcTemplate.query(select() + " WHERE r.requested_by=? AND r.idempotency_key=?", mapper(), userId, key);
        if (!duplicate.isEmpty()) {
            if (duplicate.get(0).orderId() != orderId) throw conflict("该幂等键已用于另一订单");
            return duplicate.get(0);
        }
        validateRefundable(order);
        Integer active = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_refund
                WHERE order_id=? AND refund_kind='ORDER_REFUND'
                  AND status IN ('PENDING_REVIEW','PROCESSING','SUCCESS')
                """, Integer.class, orderId);
        if (active != null && active > 0) throw conflict("该订单已有处理中或成功的退款申请");
        long paymentId = requirePayment(orderId);
        String normalizedReason = text(reason, 255, "退款原因");
        KeyHolder keys = new GeneratedKeyHolder();
        String refundNo = "REF" + LocalDateTime.now().format(TIME) + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bf_refund(refund_no,order_id,payment_id,amount_cent,reason,status,requested_by,idempotency_key)
                    VALUES (?,?,?,?,?,'PENDING_REVIEW',?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, refundNo); ps.setLong(2, order.id()); ps.setLong(3, paymentId);
            ps.setLong(4, order.paidAmountCent()); ps.setString(5, normalizedReason);
            ps.setLong(6, userId); ps.setString(7, key); return ps;
        }, keys);
        long id = keys.getKey().longValue();
        statusLog(id, null, "PENDING_REVIEW", userId, normalizedReason, "REFUND-APPLY:" + id);
        transitionSportBooking(order.id(), "REFUND_PENDING", userId, "用户提交退款申请", "SPORT-REFUND-APPLY:" + id);
        transitionDining(order.id(), "REFUND_PENDING", userId, "用户提交退款申请", "DINING-REFUND-APPLY:" + id);
        audit(userId, "REFUND_APPLY", id, "PENDING_REVIEW");
        return find(id);
    }

    @Transactional
    public RefundApplicationResponse approve(AuthenticatedPrincipal principal, long refundId, String comment) {
        long reviewerId = ensureUser(principal);
        RefundLocked refund = lockRefund(refundId);
        if ("SUCCESS".equals(refund.status())) return find(refundId);
        if (!"PENDING_REVIEW".equals(refund.status())) throw conflict("只有待审核退款申请可以通过");
        Order order = lockOrder(refund.orderId(), null);
        validateRefundable(order);
        String channelId = refundChannelId(refund);
        jdbcTemplate.update("UPDATE bf_refund SET status='PROCESSING',reviewed_by=?,review_comment=?,reviewed_at=CURRENT_TIMESTAMP,channel_refund_id=?,previous_order_status=? WHERE id=?",
                reviewerId, optionalText(comment, 500), channelId, order.status(), refundId);
        int changed = jdbcTemplate.update("UPDATE bf_order SET status='REFUNDING' WHERE id=? AND status IN ('PAID','READY')", order.id());
        if (changed != 1) throw conflict("订单状态已变化，请刷新后重试");
        jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,'REFUNDING',?,?,?)",
                order.id(), order.status(), reviewerId, "退款审核通过，等待渠道处理", "REFUND-PROCESSING:" + refundId);
        transitionSportBooking(order.id(), "REFUNDING", reviewerId, "退款审核通过，等待渠道处理", "SPORT-REFUND-APPROVE:" + refundId);
        transitionDining(order.id(), "REFUNDING", reviewerId, "退款审核通过", "DINING-REFUND-APPROVE:" + refundId);
        statusLog(refundId, "PENDING_REVIEW", "PROCESSING", reviewerId, optionalText(comment, 500), "REFUND-APPROVE:" + refundId);
        audit(reviewerId, "REFUND_APPROVE", refundId, "PROCESSING");
        return find(refundId);
    }

    @Transactional
    public RefundApplicationResponse callbackFromWechat(
            String channelRefundId,
            String outRefundNo,
            String status,
            long refundAmountCent,
            long totalAmountCent,
            String message) {
        if (!StringUtils.hasText(channelRefundId) || !StringUtils.hasText(outRefundNo)
                || !StringUtils.hasText(status)) {
            throw invalid("Incomplete WeChat Pay refund callback");
        }
        List<RefundLocked> rows = jdbcTemplate.query("""
                SELECT id,order_id,payment_id,refund_no,refund_kind,status,
                       requested_by,reviewed_by,previous_order_status
                FROM bf_refund
                WHERE refund_no=? AND (channel_refund_id IS NULL OR channel_refund_id=?)
                ORDER BY id LIMIT 1 FOR UPDATE
                """, refundLockedMapper(), outRefundNo, channelRefundId);
        if (rows.isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "WeChat Pay refund record does not exist");
        }
        RefundLocked refund = rows.get(0);
        RefundMoney expected = jdbcTemplate.queryForObject("""
                SELECT r.amount_cent refund_amount,p.amount_cent total_amount
                FROM bf_refund r JOIN bf_payment p ON p.id=r.payment_id WHERE r.id=?
                """, (rs, n) -> new RefundMoney(
                rs.getLong("refund_amount"), rs.getLong("total_amount")), refund.id());
        if (expected == null || expected.refundAmountCent() != refundAmountCent
                || expected.totalAmountCent() != totalAmountCent) {
            throw conflict("WeChat Pay refund callback amount does not match local records");
        }
        if (StringUtils.hasText(channelRefundId)) {
            jdbcTemplate.update("""
                    UPDATE bf_refund SET channel_refund_id=COALESCE(channel_refund_id,?)
                    WHERE id=? AND (channel_refund_id IS NULL OR channel_refund_id=?)
                    """, channelRefundId, refund.id(), channelRefundId);
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SUCCESS", "CLOSED", "ABNORMAL").contains(normalized)) {
            throw invalid("Unsupported WeChat Pay refund status");
        }
        if (isAutomaticCaptureRefund(refund)) {
            if ("AUTO_REFUNDED".equals(refund.status()) || "FAILED".equals(refund.status())) {
                return find(refund.id());
            }
            if (!"PROCESSING".equals(refund.status())) {
                throw conflict("Automatic safety refund is not waiting for a WeChat Pay callback");
            }
            exceptionalCaptureRefundService.handle(refund.id(), refund.paymentId(), refund.refundKind(),
                    refund.requestedBy(), refund.status(), normalized, message);
            return find(refund.id());
        }
        if (culturalAfterSaleRefundService.handleWechatCallback(
                refund.id(), normalized, message)) {
            return find(refund.id());
        }
        if ("SUCCESS".equals(refund.status()) || "FAILED".equals(refund.status())) return find(refund.id());
        if (!"PROCESSING".equals(refund.status())) {
            throw conflict("Refund is not waiting for a WeChat Pay callback");
        }
        Long operatorId = refund.reviewedBy() == null ? refund.requestedBy() : refund.reviewedBy();
        if ("SUCCESS".equals(normalized)) {
            completeRefund(refund.id(), operatorId, lockOrder(refund.orderId(), null));
        } else if ("CLOSED".equals(normalized)) {
            jdbcTemplate.update("UPDATE bf_refund SET status='FAILED',failure_reason=? WHERE id=? AND status='PROCESSING'",
                    optionalText(message, 500), refund.id());
            jdbcTemplate.update("UPDATE bf_order SET status=? WHERE id=? AND status='REFUNDING'",
                    refund.previousOrderStatus(), refund.orderId());
            transitionSportBooking(refund.orderId(), "CONFIRMED", operatorId,
                    "WeChat Pay refund closed", "SPORT-WECHAT-REFUND-CLOSED:" + refund.id());
            transitionDining(refund.orderId(), "PAID", operatorId,
                    "WeChat Pay refund closed", "DINING-WECHAT-REFUND-CLOSED:" + refund.id());
            statusLog(refund.id(), "PROCESSING", "FAILED", operatorId,
                    optionalText(message, 500), "WECHAT-REFUND-CLOSED:" + refund.id());
        } else if ("ABNORMAL".equals(normalized)) {
            // An abnormal refund may still require channel-side recovery. Do not restore
            // the local order or declare failure until a definitive CLOSED notification.
            jdbcTemplate.update("UPDATE bf_refund SET failure_reason=? WHERE id=? AND status='PROCESSING'",
                    optionalText(message, 500), refund.id());
            audit(operatorId, "WECHAT_REFUND_ABNORMAL", refund.id(), "PROCESSING");
        }
        return find(refund.id());
    }

    private static boolean isAutomaticCaptureRefund(RefundLocked refund) {
        return Set.of("AUTO_DUPLICATE_CAPTURE", "AUTO_LATE_CAPTURE").contains(refund.refundKind());
    }

    @Transactional
    public RefundApplicationResponse reject(AuthenticatedPrincipal principal, long refundId, String comment) {
        String reason = text(comment, 500, "拒绝原因");
        long reviewerId = ensureUser(principal);
        RefundLocked refund = lockRefund(refundId);
        if (!"PENDING_REVIEW".equals(refund.status())) throw conflict("只有待审核退款申请可以拒绝");
        jdbcTemplate.update("UPDATE bf_refund SET status='REJECTED',reviewed_by=?,review_comment=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",
                reviewerId, reason, refundId);
        transitionSportBooking(refund.orderId(), "CONFIRMED", reviewerId, "退款申请被拒绝", "SPORT-REFUND-REJECT:" + refundId);
        transitionDining(refund.orderId(), "PAID", reviewerId, "退款申请被拒绝", "DINING-REFUND-REJECT:" + refundId);
        statusLog(refundId, "PENDING_REVIEW", "REJECTED", reviewerId, reason, "REFUND-REJECT:" + refundId);
        audit(reviewerId, "REFUND_REJECT", refundId, "REJECTED");
        return find(refundId);
    }

    private String refundChannelId(RefundLocked refund) {
        RefundPayment payment = jdbcTemplate.queryForObject("""
                SELECT p.channel,p.payment_no,p.transaction_id,p.amount_cent,r.amount_cent refund_amount,r.reason
                FROM bf_refund r JOIN bf_payment p ON p.id=r.payment_id
                WHERE r.id=? AND p.status='SUCCESS'
                """, (rs, n) -> new RefundPayment(
                rs.getString("channel"), rs.getString("payment_no"), rs.getString("transaction_id"),
                rs.getLong("amount_cent"), rs.getLong("refund_amount"), rs.getString("reason")), refund.id());
        if (payment == null || !"WECHAT_PAY".equals(payment.channel())) {
            return "MOCKREF:" + refund.refundNo();
        }
        wechatRefundDispatchService.enqueue(refund.id(), new WechatPayV3Provider.RefundCommand(
                refund.refundNo(), payment.paymentNo(), payment.transactionId(),
                payment.refundAmountCent(), payment.totalAmountCent(), "CNY", payment.reason()));
        return null;
    }

    @Transactional(readOnly=true)
    public PageResponse<RefundApplicationResponse> listMine(AuthenticatedPrincipal principal, int page, int pageSize) {
        return list(" WHERE r.requested_by=?", List.of(requireUser(principal)), page, pageSize);
    }

    @Transactional(readOnly=true)
    public PageResponse<RefundApplicationResponse> listAdmin(String status, int page, int pageSize) {
        List<Object> args = new ArrayList<>();
        String where = "";
        if (StringUtils.hasText(status)) { where = " WHERE r.status=?"; args.add(status.trim().toUpperCase(Locale.ROOT)); }
        return list(where, args, page, pageSize);
    }

    @Transactional(readOnly=true)
    public RefundApplicationResponse detailMine(AuthenticatedPrincipal principal, long refundId) {
        long userId = requireUser(principal);
        List<RefundApplicationResponse> rows = jdbcTemplate.query(select() + " WHERE r.id=? AND r.requested_by=?", mapper(), refundId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "退款申请不存在或无权访问");
        return rows.get(0);
    }

    @Transactional(readOnly=true)
    public RefundApplicationResponse detailAdmin(long refundId) { return find(refundId); }

    private PageResponse<RefundApplicationResponse> list(String where, List<Object> args, int page, int pageSize) {
        validatePage(page, pageSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_refund r" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((page - 1) * pageSize);
        List<RefundApplicationResponse> items = jdbcTemplate.query(select() + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", mapper(), pageArgs.toArray());
        long count = total == null ? 0 : total;
        return new PageResponse<>(items, page, pageSize, count, count == 0 ? 0 : (int)((count + pageSize - 1) / pageSize));
    }

    private void validateRefundable(Order order) {
        if (!Set.of("SCENIC_TICKET", "SPORT_VENUE", "DINING", "HOTEL_ROOM").contains(order.orderType()))
            throw conflict("当前退款流程不支持该订单类型");
        if (!OrderStatePolicy.canRefund(order.status())) throw conflict("只有已支付且未使用的订单可以申请退款，当前状态为 " + order.status());
        if ("SPORT_VENUE".equals(order.orderType())) {
            LocalDateTime startAt = jdbcTemplate.queryForObject("""
                    SELECT TIMESTAMP(booking_date,start_time) FROM bf_sport_booking WHERE order_id=?
                    """, LocalDateTime.class, order.id());
            if (startAt == null || !startAt.isAfter(LocalDateTime.now())) throw conflict("场地预订开始后不能在线退款");
        }
        if ("DINING".equals(order.orderType())) {
            String state = jdbcTemplate.queryForObject("SELECT status FROM bf_dining_reservation WHERE order_id=?", String.class, order.id());
            if (!"PAID".equals(state)) throw conflict("餐饮订单已开始备餐或履约，不能在线退款");
        }
        if ("HOTEL_ROOM".equals(order.orderType())) {
            LocalDate checkInDate = jdbcTemplate.queryForObject(
                    "SELECT check_in_date FROM bf_hotel_reservation WHERE order_id=?", LocalDate.class, order.id());
            if (checkInDate == null || !checkInDate.isAfter(LocalDate.now())) {
                throw conflict("入住日开始后不能在线申请住宿退款");
            }
        }
        Integer used = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id WHERE i.order_id=? AND v.status='USED'", Integer.class, order.id());
        if (used != null && used > 0) throw conflict("订单中已有电子票核销，不能整单退款");
    }

    private void restoreInventory(Order order) {
        if ("DINING".equals(order.orderType())) {
            Long reservationId=jdbcTemplate.queryForObject("SELECT id FROM bf_dining_reservation WHERE order_id=?",Long.class,order.id());
            List<DiningStock> rows=jdbcTemplate.query("""
                    SELECT x.dish_id,SUM(x.qty) qty FROM (
                    SELECT i.dish_id,i.quantity qty FROM bf_dining_reservation_item i WHERE i.reservation_id=? AND i.item_type='DISH'
                    UNION ALL SELECT pi.dish_id,pi.quantity*i.quantity FROM bf_dining_reservation_item i JOIN bf_dining_package_item pi ON pi.package_id=i.package_id WHERE i.reservation_id=? AND i.item_type='PACKAGE')x GROUP BY x.dish_id
                    """,(rs,n)->new DiningStock(rs.getLong(1),rs.getInt(2)),reservationId,reservationId);
            for(DiningStock row:rows) jdbcTemplate.update("UPDATE bf_dining_stock SET on_hand_quantity=on_hand_quantity+?,version=version+1 WHERE dish_id=?",row.quantity(),row.dishId());
            return;
        }
        if ("VEHICLE_RENTAL".equals(order.orderType()) || "VEHICLE_INTERNAL".equals(order.orderType())) return;
        List<Item> items = jdbcTemplate.query("SELECT i.id,i.quantity,inv.id inventory_id FROM bf_order_item i JOIN bf_inventory inv ON inv.sku_id=i.sku_id AND inv.business_date=i.service_date AND inv.time_slot=COALESCE(i.time_slot,'') WHERE i.order_id=? FOR UPDATE",
                (rs,n)->new Item(rs.getLong("id"),rs.getInt("quantity"),rs.getLong("inventory_id")), order.id());
        Integer orderItemCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_order_item WHERE order_id=?", Integer.class, order.id());
        if (orderItemCount == null || items.size() != orderItemCount) throw conflict("订单库存记录不完整，已停止退款，请联系管理员");
        for (Item item : items) {
            String key = "REFUND-RELEASE:" + order.orderNo() + ":" + item.orderItemId();
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_inventory_log WHERE idempotency_key=?", Integer.class, key);
            if (exists != null && exists > 0) continue;
            jdbcTemplate.update("UPDATE bf_inventory SET available_quantity=available_quantity+?,version=version+1 WHERE id=?", item.quantity(), item.inventoryId());
            Integer after = jdbcTemplate.queryForObject("SELECT available_quantity FROM bf_inventory WHERE id=?", Integer.class, item.inventoryId());
            jdbcTemplate.update("INSERT INTO bf_inventory_log(inventory_id,order_no,change_type,quantity_delta,quantity_after,idempotency_key,remark) VALUES (?,?,'REFUND_RELEASE',?,?,?,'审核退款归还库存')",
                    item.inventoryId(), order.orderNo(), item.quantity(), after, key);
        }
    }

    private void completeRefund(long refundId, long operatorId, Order order) {
        restoreInventory(order);
        restoreMemberBalance(refundId, order);
        jdbcTemplate.update("UPDATE bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id SET v.status='VOID' WHERE i.order_id=? AND v.status='UNUSED'", order.id());
        int changed = jdbcTemplate.update("UPDATE bf_order SET status='REFUNDED' WHERE id=? AND status='REFUNDING'", order.id());
        if (changed != 1) throw conflict("订单状态已变化，请刷新后重试");
        jdbcTemplate.update("UPDATE bf_refund SET status='SUCCESS',refunded_at=CURRENT_TIMESTAMP,failure_reason=NULL WHERE id=? AND status='PROCESSING'", refundId);
        transitionSportBooking(order.id(), "REFUNDED", operatorId, "退款渠道处理成功，释放场地时段", "SPORT-REFUND-SUCCESS:" + refundId);
        transitionDining(order.id(), "REFUNDED", operatorId, "退款成功，恢复菜品库存", "DINING-REFUND-SUCCESS:" + refundId);
        if ("VEHICLE_RENTAL".equals(order.orderType())) {
            jdbcTemplate.update("UPDATE bf_vehicle_deposit SET deducted_amount_cent=0,refunded_amount_cent=deposit_amount_cent,status='REFUNDED',refunded_at=CURRENT_TIMESTAMP WHERE order_id=?", order.id());
        }
        jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,'REFUNDED',?,?,?)",
                order.id(), order.status(), operatorId, "退款执行成功", "REFUND-COMPLETE:" + refundId);
        statusLog(refundId, "PROCESSING", "SUCCESS", operatorId, "退款渠道处理成功", "REFUND-CALLBACK-SUCCESS:" + refundId);
        audit(operatorId, "REFUND_COMPLETE", refundId, "SUCCESS");
    }

    private void restoreMemberBalance(long refundId, Order order) {
        Integer balancePayment = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bf_payment WHERE order_id=? AND status='SUCCESS' AND channel='BALANCE'",
                Integer.class, order.id());
        if (balancePayment == null || balancePayment == 0) return;
        List<MemberBalance> accounts = jdbcTemplate.query("""
                SELECT m.id,m.balance_cent,m.version FROM bf_member_account m
                JOIN bf_order o ON o.user_id=m.user_id WHERE o.id=? FOR UPDATE
                """, (rs,n)->new MemberBalance(rs.getLong("id"),rs.getLong("balance_cent"),rs.getInt("version")), order.id());
        if (accounts.isEmpty()) throw conflict("余额支付会员账户不存在，已停止退款");
        MemberBalance account=accounts.get(0); String key="ORDER_REFUND:"+refundId;
        Integer exists=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_sport_wallet_transaction WHERE member_account_id=? AND idempotency_key=?",Integer.class,account.id(),key);
        if (exists!=null&&exists>0) return;
        long after=Math.addExact(account.balance(),order.paidAmountCent());
        if (jdbcTemplate.update("UPDATE bf_member_account SET balance_cent=?,version=version+1 WHERE id=? AND version=?",after,account.id(),account.version())!=1)
            throw conflict("会员余额已变化，请重试退款");
        jdbcTemplate.update("""
                INSERT INTO bf_sport_wallet_transaction
                (member_account_id,transaction_no,transaction_type,amount_cent,balance_before_cent,balance_after_cent,
                 order_id,reference_no,idempotency_key,remark)
                VALUES (?,CONCAT('WR',DATE_FORMAT(NOW(),'%Y%m%d%H%i%s'),LPAD(?,12,'0')),'REFUND',?,?,?,?,?,?, '体育场地订单退款退回余额')
                """,account.id(),refundId,order.paidAmountCent(),account.balance(),after,order.id(),order.orderNo(),key);
    }

    private Order lockOrder(long id, Long userId) {
        String sql = "SELECT id,order_no,order_type,status,paid_amount_cent FROM bf_order WHERE id=?" + (userId == null ? "" : " AND user_id=?") + " FOR UPDATE";
        List<Order> rows = jdbcTemplate.query(sql,(rs,n)->new Order(rs.getLong("id"),rs.getString("order_no"),rs.getString("order_type"),rs.getString("status"),rs.getLong("paid_amount_cent")),
                userId == null ? new Object[]{id} : new Object[]{id,userId});
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND,"订单不存在或无权访问");
        return rows.get(0);
    }

    private RefundLocked lockRefund(long id) {
        List<RefundLocked> rows=jdbcTemplate.query("SELECT id,order_id,payment_id,refund_no,refund_kind,status,requested_by,reviewed_by,previous_order_status FROM bf_refund WHERE id=? FOR UPDATE",refundLockedMapper(),id);
        if(rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND,"退款申请不存在"); return rows.get(0);
    }
    private org.springframework.jdbc.core.RowMapper<RefundLocked> refundLockedMapper(){return(rs,n)->new RefundLocked(rs.getLong("id"),rs.getLong("order_id"),rs.getLong("payment_id"),rs.getString("refund_no"),rs.getString("refund_kind"),rs.getString("status"),rs.getLong("requested_by"),rs.getObject("reviewed_by",Long.class),rs.getString("previous_order_status"));}

    private long requirePayment(long orderId) {
        List<Long> ids=jdbcTemplate.queryForList("SELECT id FROM bf_payment WHERE order_id=? AND status='SUCCESS' ORDER BY id LIMIT 1",Long.class,orderId);
        if(ids.isEmpty()) throw conflict("未找到成功支付流水"); return ids.get(0);
    }

    private void transitionSportBooking(long orderId, String toStatus, Long operatorId, String note, String key) {
        List<SportBookingState> rows = jdbcTemplate.query("SELECT id,status FROM bf_sport_booking WHERE order_id=? FOR UPDATE",
                (rs, n) -> new SportBookingState(rs.getLong("id"), rs.getString("status")), orderId);
        if (rows.isEmpty()) return;
        SportBookingState booking = rows.get(0);
        if ("REFUNDED".equals(toStatus)) {
            jdbcTemplate.update("UPDATE bf_sport_booking SET status=?,refunded_at=COALESCE(refunded_at,CURRENT_TIMESTAMP) WHERE id=?",
                    toStatus, booking.id());
        } else {
            jdbcTemplate.update("UPDATE bf_sport_booking SET status=? WHERE id=?", toStatus, booking.id());
        }
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_sport_booking_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key) VALUES (?,?,?,?,?,?)
                """, booking.id(), booking.status(), toStatus, operatorId, note, key);
    }

    private void transitionDining(long orderId,String toStatus,Long operatorId,String note,String key){
        List<DiningState> rows=jdbcTemplate.query("SELECT id,status FROM bf_dining_reservation WHERE order_id=? FOR UPDATE",
                (rs,n)->new DiningState(rs.getLong(1),rs.getString(2)),orderId);
        if(rows.isEmpty())return;DiningState r=rows.get(0);
        jdbcTemplate.update("UPDATE bf_dining_reservation SET status=? WHERE id=?",toStatus,r.id());
        jdbcTemplate.update("INSERT IGNORE INTO bf_dining_status_log(reservation_id,from_status,to_status,operator_id,note,idempotency_key) VALUES(?,?,?,?,?,?)",
                r.id(),r.status(),toStatus,operatorId,note,key);
        if("REFUNDED".equals(toStatus))jdbcTemplate.update("UPDATE bf_dining_kitchen_ticket SET status='CANCELLED',updated_by=? WHERE reservation_id=? AND status IN('NEW','ACCEPTED')",operatorId,r.id());
    }

    private long requireUser(AuthenticatedPrincipal p) { List<Long> ids=jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId()); if(ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND,"当前用户尚无业务数据"); return ids.get(0); }
    private long ensureUser(AuthenticatedPrincipal p) { jdbcTemplate.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName()); return requireUser(p); }
    private String text(String v,int max,String field){if(!StringUtils.hasText(v)||v.trim().length()>max)throw invalid(field+"不能为空且最长"+max+"个字符");return v.trim();}
    private String optionalText(String v,int max){if(!StringUtils.hasText(v))return null;if(v.trim().length()>max)throw invalid("审核备注最长"+max+"个字符");return v.trim();}
    private void validatePage(int p,int s){if(p<1||s<1||s>100)throw invalid("page最小为1，pageSize范围为1—100");}
    private BusinessException invalid(String m){return new BusinessException(CommonErrorCode.INVALID_REQUEST,m);} private BusinessException conflict(String m){return new BusinessException(CommonErrorCode.REFUND_CONFLICT,m);}
    private void audit(long user,String action,long id,String status){jdbcTemplate.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,'REFUND',?,JSON_OBJECT('status',?))",user,action,String.valueOf(id),status);}
    private void statusLog(long refundId,String from,String to,Long operator,String note,String key){jdbcTemplate.update("INSERT IGNORE INTO bf_refund_status_log(refund_id,from_status,to_status,operator_id,note,idempotency_key) VALUES (?,?,?,?,?,?)",refundId,from,to,operator,note,key);}
    private RefundApplicationResponse find(long id){return jdbcTemplate.queryForObject(select()+" WHERE r.id=?",mapper(),id);}
    private String select(){return "SELECT r.id,r.order_id,o.order_no,r.refund_no,r.channel_refund_id,r.amount_cent,r.reason,r.status,r.refund_kind,r.automatic_capture_payment_id,r.requested_by,r.reviewed_by,u.nickname reviewer_name,r.review_comment,r.failure_reason,r.created_at,r.reviewed_at,r.refunded_at,d.dispatch_status,d.attempt_count dispatch_attempt_count,d.last_error dispatch_last_error FROM bf_refund r JOIN bf_order o ON o.id=r.order_id LEFT JOIN bf_user u ON u.id=r.reviewed_by LEFT JOIN bf_wechat_refund_dispatch d ON d.refund_id=r.id";}
    private org.springframework.jdbc.core.RowMapper<RefundApplicationResponse> mapper(){return(rs,n)->new RefundApplicationResponse(rs.getLong("id"),rs.getLong("order_id"),rs.getString("order_no"),rs.getString("refund_no"),rs.getString("channel_refund_id"),rs.getLong("amount_cent"),"CNY",rs.getString("reason"),rs.getString("status"),rs.getString("refund_kind"),rs.getObject("automatic_capture_payment_id",Long.class)!=null,rs.getString("dispatch_status"),rs.getInt("dispatch_attempt_count"),rs.getString("dispatch_last_error"),rs.getObject("requested_by",Long.class),rs.getObject("reviewed_by",Long.class),rs.getString("reviewer_name"),rs.getString("review_comment"),rs.getString("failure_reason"),rs.getObject("created_at",LocalDateTime.class),rs.getObject("reviewed_at",LocalDateTime.class),rs.getObject("refunded_at",LocalDateTime.class));}
    private record Order(long id,String orderNo,String orderType,String status,long paidAmountCent){} private record Item(long orderItemId,int quantity,long inventoryId){} private record MemberBalance(long id,long balance,int version){} private record RefundLocked(long id,long orderId,long paymentId,String refundNo,String refundKind,String status,long requestedBy,Long reviewedBy,String previousOrderStatus){} private record SportBookingState(long id,String status){} private record DiningState(long id,String status){} private record DiningStock(long dishId,int quantity){} private record RefundPayment(String channel,String paymentNo,String transactionId,long totalAmountCent,long refundAmountCent,String reason){} private record RefundMoney(long refundAmountCent,long totalAmountCent){}
}
