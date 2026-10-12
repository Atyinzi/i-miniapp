package com.iminiapp.backend.cultural.application;

import com.iminiapp.backend.cultural.api.CulturalAfterSaleModels.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.application.WechatPayV3Provider;
import com.iminiapp.backend.payment.application.WechatRefundDispatchService;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CulturalAfterSaleService {
    private static final Set<String> ACTIVE = Set.of(
            "PENDING_REVIEW", "WAITING_RETURN", "RETURNED", "REFUNDING", "REFUND_FAILED");
    private static final Pattern JSON_STRING = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final JdbcTemplate jdbcTemplate;
    private final WechatRefundDispatchService wechatRefundDispatchService;

    public CulturalAfterSaleService(
            JdbcTemplate jdbcTemplate,
            WechatRefundDispatchService wechatRefundDispatchService) {
        this.jdbcTemplate = jdbcTemplate;
        this.wechatRefundDispatchService = wechatRefundDispatchService;
    }

    @Transactional
    public AfterSaleResponse apply(AuthenticatedPrincipal principal, long orderId, String key, ApplyRequest request) {
        requireKey(key);
        long userId = requireUser(principal);
        List<Long> duplicate = jdbcTemplate.queryForList("SELECT id FROM bf_cultural_after_sale WHERE requested_by=? AND idempotency_key=?", Long.class, userId, key);
        if (!duplicate.isEmpty()) {
            long existingOrder = jdbcTemplate.queryForObject("SELECT order_id FROM bf_cultural_after_sale WHERE id=?", Long.class, duplicate.get(0));
            if (existingOrder != orderId) throw conflict("该幂等键已用于其他订单");
            return detail(duplicate.get(0), userId);
        }
        OrderLocked order = lockOrder(orderId, userId);
        String type = required(request == null ? null : request.type(), 32, "售后类型").toUpperCase(Locale.ROOT);
        if (!Set.of("REFUND_ONLY", "RETURN_REFUND").contains(type)) throw invalid("售后类型仅支持REFUND_ONLY或RETURN_REFUND");
        validateApplyStatus(order, type);
        Integer active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_cultural_after_sale WHERE order_id=? AND status IN ('PENDING_REVIEW','WAITING_RETURN','RETURNED','REFUNDING','REFUND_FAILED')", Integer.class, orderId);
        if (active != null && active > 0) throw conflict("该订单已有进行中的售后申请");
        String reason = required(request.reason(), 500, "售后原因");
        String evidence = evidenceJson(request.evidenceUrls());
        String no = "CAS" + LocalDateTime.now().format(NO_TIME) + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bf_cultural_after_sale(after_sale_no,order_id,after_sale_type,reason,evidence_urls,
                    status,refund_amount_cent,requested_by,idempotency_key,previous_order_status)
                    VALUES (?,?,?,?,CAST(? AS JSON),'PENDING_REVIEW',?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, no); ps.setLong(2, orderId); ps.setString(3, type); ps.setString(4, reason);
            ps.setString(5, evidence); ps.setLong(6, order.paidAmountCent()); ps.setLong(7, userId);
            ps.setString(8, key); ps.setString(9, order.status()); return ps;
        }, keys);
        long id = keys.getKey().longValue();
        log(id, "APPLY", null, "PENDING_REVIEW", userId, reason, "APPLY:" + id);
        audit(userId, "CULTURAL_AFTER_SALE_APPLY", id, "PENDING_REVIEW");
        return detail(id, userId);
    }

    @Transactional
    public AfterSaleResponse cancel(AuthenticatedPrincipal principal, long id, String key) {
        requireKey(key); long userId = requireUser(principal);
        if (operationDone(id, key)) return detail(id, userId);
        CaseLocked c = lockCase(id, userId);
        if (!"PENDING_REVIEW".equals(c.status())) throw conflict("只有待审核售后申请可以取消");
        jdbcTemplate.update("UPDATE bf_cultural_after_sale SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=?", id);
        log(id, "CANCEL", c.status(), "CANCELLED", userId, "用户取消售后", key);
        return detail(id, userId);
    }

    @Transactional
    public AfterSaleResponse approve(AuthenticatedPrincipal principal, long id, String key, ApproveRequest request) {
        requireKey(key); long operator = ensureUser(principal);
        if (operationDone(id, key)) return detail(id, null);
        CaseLocked c = lockCase(id, null);
        if (!"PENDING_REVIEW".equals(c.status())) throw conflict("只有待审核售后申请可以审核通过");
        String comment = optional(request == null ? null : request.comment(), 500);
        if ("RETURN_REFUND".equals(c.type())) {
            String address = required(request == null ? null : request.returnAddress(), 500, "退货地址");
            jdbcTemplate.update("UPDATE bf_cultural_after_sale SET status='WAITING_RETURN',reviewed_by=?,review_comment=?,reviewed_at=CURRENT_TIMESTAMP,return_address=? WHERE id=?",
                    operator, comment, address, id);
            log(id, "APPROVE_RETURN", c.status(), "WAITING_RETURN", operator, comment, key);
            notifyUser(c.userId(), c.orderId(), "退货申请审核通过", "请按售后单提供的退货地址寄回商品");
        } else {
            startRefund(c, operator, comment);
            log(id, "APPROVE_REFUND", c.status(), "REFUNDING", operator, comment, key);
            notifyUser(c.userId(), c.orderId(), "退款申请审核通过", "退款正在处理中");
        }
        audit(operator, "CULTURAL_AFTER_SALE_APPROVE", id, c.type());
        return detail(id, null);
    }

    @Transactional
    public AfterSaleResponse reject(AuthenticatedPrincipal principal, long id, String key, ReviewRequest request) {
        requireKey(key); long operator = ensureUser(principal);
        if (operationDone(id, key)) return detail(id, null);
        CaseLocked c = lockCase(id, null);
        if (!"PENDING_REVIEW".equals(c.status())) throw conflict("只有待审核售后申请可以拒绝");
        String comment = required(request == null ? null : request.comment(), 500, "拒绝原因");
        jdbcTemplate.update("UPDATE bf_cultural_after_sale SET status='REJECTED',reviewed_by=?,review_comment=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?", operator, comment, id);
        log(id, "REJECT", c.status(), "REJECTED", operator, comment, key);
        notifyUser(c.userId(), c.orderId(), "售后申请未通过", comment);
        audit(operator, "CULTURAL_AFTER_SALE_REJECT", id, "REJECTED");
        return detail(id, null);
    }

    @Transactional
    public AfterSaleResponse submitReturn(AuthenticatedPrincipal principal, long id, String key, ReturnShipmentRequest request) {
        requireKey(key); long userId = requireUser(principal);
        if (operationDone(id, key)) return detail(id, userId);
        CaseLocked c = lockCase(id, userId);
        if (!"WAITING_RETURN".equals(c.status())) throw conflict("当前售后状态不能填写退货物流");
        String carrierCode = required(request == null ? null : request.carrierCode(), 32, "物流公司").toUpperCase(Locale.ROOT);
        Carrier carrier = carrier(carrierCode);
        String tracking = required(request.trackingNo(), 64, "物流单号");
        jdbcTemplate.update("INSERT INTO bf_cultural_after_sale_return(after_sale_id,carrier_code,carrier_name,tracking_no,remark) VALUES (?,?,?,?,?)",
                id, carrier.code(), carrier.name(), tracking, optional(request.remark(), 255));
        jdbcTemplate.update("UPDATE bf_cultural_after_sale SET status='RETURNED' WHERE id=?", id);
        log(id, "SUBMIT_RETURN", c.status(), "RETURNED", userId, carrier.name() + " " + tracking, key);
        return detail(id, userId);
    }

    @Transactional
    public AfterSaleResponse receiveReturn(AuthenticatedPrincipal principal, long id, String key, ReviewRequest request) {
        requireKey(key); long operator = ensureUser(principal);
        if (operationDone(id, key)) return detail(id, null);
        CaseLocked c = lockCase(id, null);
        if (!"RETURNED".equals(c.status())) throw conflict("只有已寄回的售后单可以确认收货");
        Integer returns = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_cultural_after_sale_return WHERE after_sale_id=?", Integer.class, id);
        if (returns == null || returns == 0) throw conflict("退货物流不存在");
        restoreStock(c.orderId(), c.orderNo(), id, operator);
        jdbcTemplate.update("UPDATE bf_cultural_after_sale_return SET received_at=CURRENT_TIMESTAMP,received_by=? WHERE after_sale_id=?", operator, id);
        startRefund(c, operator, optional(request == null ? null : request.comment(), 500));
        log(id, "RECEIVE_RETURN", c.status(), "REFUNDING", operator, optional(request == null ? null : request.comment(), 500), key);
        notifyUser(c.userId(), c.orderId(), "退货已签收", "商家已签收退货，退款正在处理中");
        return detail(id, null);
    }

    @Transactional
    public AfterSaleResponse retryRefund(
            AuthenticatedPrincipal principal,
            long id,
            String key,
            ReviewRequest request) {
        requireKey(key);
        long operator = ensureUser(principal);
        if (operationDone(id, key)) return detail(id, null);
        CaseLocked c = lockCase(id, null);
        if (!"REFUND_FAILED".equals(c.status())) {
            throw conflict("只有渠道退款失败的售后单可以重新发起退款");
        }
        String comment = optional(request == null ? null : request.comment(), 500);
        startRefund(c, operator, comment);
        log(id, "RETRY_CHANNEL_REFUND", "REFUND_FAILED", "REFUNDING", operator, comment, key);
        notifyUser(c.userId(), c.orderId(), "退款已重新提交", "退款请求已重新提交至微信支付渠道");
        audit(operator, "CULTURAL_AFTER_SALE_RETRY_REFUND", id, "REFUNDING");
        return detail(id, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<AfterSaleResponse> mine(AuthenticatedPrincipal principal, int page, int pageSize) {
        return list(" WHERE a.requested_by=?", List.of(requireUser(principal)), page, pageSize);
    }
    @Transactional(readOnly = true)
    public AfterSaleResponse mineDetail(AuthenticatedPrincipal principal, long id) { return detail(id, requireUser(principal)); }
    @Transactional(readOnly = true)
    public PageResponse<AfterSaleResponse> adminList(String status, String type, int page, int pageSize) {
        List<Object> args = new ArrayList<>(); StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (StringUtils.hasText(status)) { where.append(" AND a.status=?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        if (StringUtils.hasText(type)) { where.append(" AND a.after_sale_type=?"); args.add(type.trim().toUpperCase(Locale.ROOT)); }
        return list(where.toString(), args, page, pageSize);
    }
    @Transactional(readOnly = true)
    public AfterSaleResponse adminDetail(long id) { return detail(id, null); }

    private void startRefund(CaseLocked c, long operator, String comment) {
        List<PaymentForRefund> payments = jdbcTemplate.query("""
                SELECT id,payment_no,channel,transaction_id,amount_cent
                FROM bf_payment
                WHERE order_id=? AND status='SUCCESS'
                ORDER BY id LIMIT 1 FOR UPDATE
                """, (rs, n) -> new PaymentForRefund(
                rs.getLong("id"), rs.getString("payment_no"), rs.getString("channel"),
                rs.getString("transaction_id"), rs.getLong("amount_cent")), c.orderId());
        if (payments.isEmpty()) throw conflict("未找到可退款的成功支付流水");
        PaymentForRefund payment = payments.get(0);
        if (!"WECHAT_PAY".equals(payment.channel()) || !StringUtils.hasText(payment.transactionId())) {
            throw conflict("该订单不是可原路退回的微信支付订单，请联系财务处理");
        }
        if (c.amountCent() <= 0 || c.amountCent() > payment.amountCent()) {
            throw conflict("售后退款金额与原支付流水不一致");
        }
        String refundReason = c.reason().substring(0, Math.min(255, c.reason().length()));
        String refundNo = "REF" + LocalDateTime.now().format(NO_TIME) + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bf_refund(refund_no,order_id,payment_id,channel_refund_id,amount_cent,reason,status,
                    requested_by,idempotency_key,reviewed_by,review_comment,reviewed_at,previous_order_status)
                    VALUES (?,?,?,?,?,?,'PROCESSING',?,?,?,?,CURRENT_TIMESTAMP,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, refundNo); ps.setLong(2, c.orderId()); ps.setLong(3, payment.id());
            ps.setString(4, null); ps.setLong(5, c.amountCent()); ps.setString(6, refundReason);
            ps.setLong(7, c.userId()); ps.setString(8, "CULTURAL-" + c.id() + "-" + refundNo); ps.setLong(9, operator);
            ps.setString(10, comment); ps.setString(11, c.orderStatus()); return ps;
        }, keys);
        if (keys.getKey() == null) throw conflict("退款单创建失败");
        long refundId = keys.getKey().longValue();
        // enqueue() validates that the real WeChat Pay provider is fully configured.
        // Any exception rolls this surrounding transaction back, so approval can never
        // be half-written as REFUNDING without a durable channel request.
        wechatRefundDispatchService.enqueue(refundId, new WechatPayV3Provider.RefundCommand(
                refundNo, payment.paymentNo(), payment.transactionId(), c.amountCent(),
                payment.amountCent(), "CNY", refundReason));
        int changed = jdbcTemplate.update("UPDATE bf_order SET status='REFUNDING' WHERE id=? AND status=?", c.orderId(), c.orderStatus());
        if (changed != 1) throw conflict("订单状态已变化，请刷新后重试");
        jdbcTemplate.update("UPDATE bf_cultural_after_sale SET status='REFUNDING',reviewed_by=?,review_comment=?,reviewed_at=COALESCE(reviewed_at,CURRENT_TIMESTAMP),refund_id=? WHERE id=?",
                operator, comment, refundId, c.id());
        jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,'REFUNDING',?,?,?)",
                c.orderId(), c.orderStatus(), operator, "文创售后进入退款处理", "CULTURAL-REFUNDING:" + c.id());
        jdbcTemplate.update("INSERT IGNORE INTO bf_refund_status_log(refund_id,from_status,to_status,operator_id,note,idempotency_key) VALUES (?,NULL,'PROCESSING',?,?,?)",
                refundId, operator, comment, "CULTURAL-REFUND-CREATE:" + c.id());
    }

    private void restoreStock(long orderId, String orderNo, long caseId, long operator) {
        List<StockItem> items = jdbcTemplate.query("SELECT id,sku_id,quantity FROM bf_order_item WHERE order_id=? AND resource_type='CULTURAL_PRODUCT' ORDER BY id",
                (rs, n) -> new StockItem(rs.getLong("id"), rs.getLong("sku_id"), rs.getInt("quantity")), orderId);
        if (items.isEmpty()) throw conflict("文创订单商品不存在");
        for (StockItem item : items) {
            String stockKey = "AFTER-SALE-RETURN:" + caseId + ":" + item.itemId();
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_cultural_stock_log WHERE idempotency_key=?", Integer.class, stockKey);
            if (exists != null && exists > 0) continue;
            int changed = jdbcTemplate.update("UPDATE bf_cultural_stock SET on_hand_quantity=on_hand_quantity+?,available_quantity=available_quantity+?,version=version+1 WHERE sku_id=?",
                    item.quantity(), item.quantity(), item.skuId());
            if (changed != 1) throw conflict("商品库存记录不存在，无法完成退货入库");
            jdbcTemplate.update("""
                    INSERT INTO bf_cultural_stock_log(sku_id,change_type,quantity_delta,on_hand_after,available_after,
                    idempotency_key,operator_id,reference_no,remark)
                    SELECT ?,'RETURN',?,on_hand_quantity,available_quantity,?,?,?,'售后退款/退货入库'
                    FROM bf_cultural_stock WHERE sku_id=?
                    """, item.skuId(), item.quantity(), stockKey, operator, orderNo, item.skuId());
        }
    }

    private PageResponse<AfterSaleResponse> list(String where, List<Object> args, int page, int pageSize) {
        validatePage(page, pageSize);
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_cultural_after_sale a" + where, Long.class, args.toArray());
        List<Object> all = new ArrayList<>(args); all.add(pageSize); all.add((page - 1) * pageSize);
        List<Long> ids = jdbcTemplate.queryForList("SELECT a.id FROM bf_cultural_after_sale a" + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?", Long.class, all.toArray());
        List<AfterSaleResponse> items = ids.stream().map(id -> detail(id, null)).toList();
        return new PageResponse<>(items, page, pageSize, total, total == 0 ? 0 : (int)((total + pageSize - 1) / pageSize));
    }

    private AfterSaleResponse detail(long id, Long owner) {
        String ownerSql = owner == null ? "" : " AND a.requested_by=?";
        Object[] args = owner == null ? new Object[]{id} : new Object[]{id, owner};
        List<AfterSaleHead> rows = jdbcTemplate.query("""
                SELECT a.id,a.after_sale_no,a.order_id,o.order_no,a.after_sale_type,a.reason,
                CAST(a.evidence_urls AS CHAR) evidence_urls,a.status,a.refund_amount_cent,a.refund_id,
                r.status refund_status,a.requested_by,a.reviewed_by,u.nickname reviewer_name,a.review_comment,
                a.return_address,a.created_at,a.reviewed_at,a.completed_at,a.cancelled_at
                FROM bf_cultural_after_sale a JOIN bf_order o ON o.id=a.order_id
                LEFT JOIN bf_refund r ON r.id=a.refund_id LEFT JOIN bf_user u ON u.id=a.reviewed_by
                WHERE a.id=?
                """ + ownerSql, (rs, n) -> new AfterSaleHead(rs.getLong("id"), rs.getString("after_sale_no"),
                rs.getLong("order_id"), rs.getString("order_no"), rs.getString("after_sale_type"),
                rs.getString("reason"), rs.getString("evidence_urls"), rs.getString("status"),
                rs.getLong("refund_amount_cent"), rs.getObject("refund_id", Long.class), rs.getString("refund_status"),
                rs.getObject("requested_by", Long.class), rs.getObject("reviewed_by", Long.class),
                rs.getString("reviewer_name"), rs.getString("review_comment"), rs.getString("return_address"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class), rs.getObject("cancelled_at", LocalDateTime.class)), args);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "售后单不存在或无权访问");
        AfterSaleHead h = rows.get(0);
        List<ReturnShipmentResponse> returns = jdbcTemplate.query("SELECT carrier_code,carrier_name,tracking_no,shipped_at,received_at,remark FROM bf_cultural_after_sale_return WHERE after_sale_id=?",
                (rs, n) -> new ReturnShipmentResponse(rs.getString("carrier_code"), rs.getString("carrier_name"),
                        rs.getString("tracking_no"), rs.getObject("shipped_at", LocalDateTime.class),
                        rs.getObject("received_at", LocalDateTime.class), rs.getString("remark")), id);
        List<StatusLogResponse> logs = jdbcTemplate.query("SELECT id,action,from_status,to_status,operator_id,note,created_at FROM bf_cultural_after_sale_log WHERE after_sale_id=? ORDER BY id",
                (rs, n) -> new StatusLogResponse(rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getObject("operator_id", Long.class), rs.getString("note"),
                        rs.getObject("created_at", LocalDateTime.class)), id);
        return new AfterSaleResponse(h.id(), h.no(), h.orderId(), h.orderNo(), h.type(), h.reason(),
                parseEvidence(h.evidence()), h.status(), h.amount(), h.refundId(), h.refundStatus(), h.requestedBy(),
                h.reviewedBy(), h.reviewerName(), h.comment(), h.returnAddress(),
                returns.isEmpty() ? null : returns.get(0), logs, h.createdAt(), h.reviewedAt(), h.completedAt(), h.cancelledAt());
    }

    private void validateApplyStatus(OrderLocked order, String type) {
        if ("REFUND_ONLY".equals(type) && !Set.of("PAID", "PREPARING").contains(order.status()))
            throw conflict("仅退款只适用于已支付且尚未发货的订单");
        if ("RETURN_REFUND".equals(type) && !Set.of("SHIPPED", "COMPLETED").contains(order.status()))
            throw conflict("退货退款只适用于已发货或已完成订单");
        if ("RETURN_REFUND".equals(type) && "COMPLETED".equals(order.status()) && order.completedAt() != null
                && order.completedAt().isBefore(LocalDateTime.now().minusDays(7))) throw conflict("订单确认收货超过7天，不能在线申请退货退款");
    }
    private OrderLocked lockOrder(long id, long owner) {
        List<OrderLocked> rows = jdbcTemplate.query("SELECT id,order_no,user_id,status,paid_amount_cent,completed_at FROM bf_order WHERE id=? AND user_id=? AND order_type='CULTURAL_PRODUCT' FOR UPDATE",
                (rs,n)->new OrderLocked(rs.getLong("id"),rs.getString("order_no"),rs.getLong("user_id"),rs.getString("status"),rs.getLong("paid_amount_cent"),rs.getObject("completed_at",LocalDateTime.class)), id, owner);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND,"文创订单不存在或无权访问");
        return rows.get(0);
    }
    private CaseLocked lockCase(long id, Long owner) {
        String filter = owner == null ? "" : " AND a.requested_by=?";
        Object[] args = owner == null ? new Object[]{id} : new Object[]{id, owner};
        List<CaseLocked> rows = jdbcTemplate.query("""
                SELECT a.id,a.order_id,o.order_no,o.status order_status,a.after_sale_type,a.status,a.reason,
                a.refund_amount_cent,a.requested_by,a.refund_id FROM bf_cultural_after_sale a
                JOIN bf_order o ON o.id=a.order_id WHERE a.id=?
                """ + filter + " FOR UPDATE", (rs,n)->new CaseLocked(rs.getLong("id"),rs.getLong("order_id"),
                rs.getString("order_no"),rs.getString("order_status"),rs.getString("after_sale_type"),
                rs.getString("status"),rs.getString("reason"),rs.getLong("refund_amount_cent"),
                rs.getLong("requested_by"),rs.getObject("refund_id",Long.class)), args);
        if(rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND,"售后单不存在或无权访问");
        return rows.get(0);
    }
    private Carrier carrier(String code) {
        List<Carrier> rows=jdbcTemplate.query("SELECT carrier_code,carrier_name FROM bf_logistics_carrier WHERE carrier_code=? AND enabled=1",
                (rs,n)->new Carrier(rs.getString("carrier_code"),rs.getString("carrier_name")),code);
        if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"物流公司不存在或已停用"); return rows.get(0);
    }
    private boolean operationDone(long id,String key){List<Long> ids=jdbcTemplate.queryForList("SELECT after_sale_id FROM bf_cultural_after_sale_log WHERE idempotency_key=?",Long.class,key);if(ids.isEmpty())return false;if(ids.get(0)!=id)throw conflict("幂等键已用于其他售后单");return true;}
    private void log(long id,String action,String from,String to,Long operator,String note,String key){jdbcTemplate.update("INSERT INTO bf_cultural_after_sale_log(after_sale_id,action,from_status,to_status,operator_id,note,idempotency_key) VALUES (?,?,?,?,?,?,?)",id,action,from,to,operator,note,key);}
    private void notifyUser(long userId,long orderId,String title,String content){jdbcTemplate.update("INSERT INTO bf_notification(user_id,notification_type,title,content,business_type,business_id) VALUES (?,'ORDER',?,?,'CULTURAL_AFTER_SALE',?)",userId,title,content,orderId);}
    private void audit(long user,String action,long id,String status){jdbcTemplate.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,'CULTURAL_AFTER_SALE',?,JSON_OBJECT('status',?))",user,action,String.valueOf(id),status);}
    private long ensureUser(AuthenticatedPrincipal p){jdbcTemplate.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return requireUser(p);}
    private long requireUser(AuthenticatedPrincipal p){List<Long> ids=jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());if(ids.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"当前用户不存在");return ids.get(0);}
    private void requireKey(String key){if(!StringUtils.hasText(key)||key.trim().length()>100)throw invalid("Idempotency-Key不能为空且最长100字符");}
    private String required(String value,int max,String name){if(!StringUtils.hasText(value))throw invalid(name+"不能为空");String v=value.trim();if(v.length()>max)throw invalid(name+"最长"+max+"字符");return v;}
    private String optional(String value,int max){return StringUtils.hasText(value)?required(value,max,"字段"):null;}
    private void validatePage(int page,int size){if(page<1||size<1||size>100)throw invalid("page从1开始，pageSize范围为1—100");}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private BusinessException conflict(String message){return new BusinessException(CommonErrorCode.REFUND_CONFLICT,message);}
    private String evidenceJson(List<String> urls){if(urls==null||urls.isEmpty())return "[]";if(urls.size()>6)throw invalid("售后凭证最多6张");List<String> values=new ArrayList<>();for(String url:urls){String v=required(url,512,"凭证地址");values.add("\""+v.replace("\\","\\\\").replace("\"","\\\"")+"\"");}return "["+String.join(",",values)+"]";}
    private List<String> parseEvidence(String json){if(!StringUtils.hasText(json))return List.of();List<String> values=new ArrayList<>();Matcher matcher=JSON_STRING.matcher(json);while(matcher.find())values.add(matcher.group(1).replace("\\\"","\"").replace("\\\\","\\"));return values;}

    private record OrderLocked(long id,String orderNo,long userId,String status,long paidAmountCent,LocalDateTime completedAt){}
    private record CaseLocked(long id,long orderId,String orderNo,String orderStatus,String type,String status,String reason,long amountCent,long userId,Long refundId){}
    private record StockItem(long itemId,long skuId,int quantity){}
    private record PaymentForRefund(long id,String paymentNo,String channel,String transactionId,long amountCent){}
    private record Carrier(String code,String name){}
    private record AfterSaleHead(long id,String no,long orderId,String orderNo,String type,String reason,String evidence,
                                 String status,long amount,Long refundId,String refundStatus,Long requestedBy,Long reviewedBy,
                                 String reviewerName,String comment,String returnAddress,LocalDateTime createdAt,
                                 LocalDateTime reviewedAt,LocalDateTime completedAt,LocalDateTime cancelledAt){}
}
