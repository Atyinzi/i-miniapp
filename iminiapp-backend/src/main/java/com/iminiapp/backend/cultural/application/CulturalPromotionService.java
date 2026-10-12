package com.iminiapp.backend.cultural.application;

import com.iminiapp.backend.cultural.api.CulturalPromotionModels.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CulturalPromotionService {
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE");
    private static final Set<String> TYPES = Set.of("FULL_REDUCTION", "DISCOUNT");
    private static final Set<String> TARGET_TYPES = Set.of("ALL", "CATEGORY", "PRODUCT", "SKU");
    private final JdbcTemplate jdbcTemplate;

    public CulturalPromotionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> promotions() {
        return jdbcTemplate.queryForList("SELECT id FROM bf_cultural_promotion ORDER BY id DESC", Long.class)
                .stream().map(this::promotion).toList();
    }

    @Transactional
    public PromotionResponse createPromotion(AuthenticatedPrincipal principal, PromotionUpsertRequest request) {
        PromotionValues value = validatePromotion(request);
        requireUnique("bf_cultural_promotion", "promotion_code", value.code(), null);
        long operator = ensureOperator(principal);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_cultural_promotion
                    (promotion_code,name,promotion_type,threshold_amount_cent,reduction_amount_cent,discount_rate,
                     priority,exclusive,start_at,end_at,status) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindPromotion(statement, value); return statement;
        }, keys);
        long id = generatedId(keys);
        replacePromotionTargets(id, request.targets());
        audit(operator, "CULTURAL_PROMOTION_CREATE", "CULTURAL_PROMOTION", id, value.code());
        return promotion(id);
    }

    @Transactional
    public PromotionResponse updatePromotion(AuthenticatedPrincipal principal, long id,
                                             PromotionUpsertRequest request) {
        promotion(id);
        PromotionValues value = validatePromotion(request);
        requireUnique("bf_cultural_promotion", "promotion_code", value.code(), id);
        long operator = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_cultural_promotion SET promotion_code=?,name=?,promotion_type=?,threshold_amount_cent=?,
                       reduction_amount_cent=?,discount_rate=?,priority=?,exclusive=?,start_at=?,end_at=?,status=?
                WHERE id=?
                """, value.code(), value.name(), value.type(), value.threshold(), value.reduction(), value.rate(),
                value.priority(), value.exclusive(), value.startAt(), value.endAt(), value.status(), id);
        replacePromotionTargets(id, request.targets());
        audit(operator, "CULTURAL_PROMOTION_UPDATE", "CULTURAL_PROMOTION", id, value.code());
        return promotion(id);
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> coupons() {
        return jdbcTemplate.queryForList("""
                SELECT id FROM bf_coupon WHERE applicable_resource_type='CULTURAL_PRODUCT' ORDER BY id DESC
                """, Long.class).stream().map(this::coupon).toList();
    }

    @Transactional
    public CouponResponse createCoupon(AuthenticatedPrincipal principal, CouponUpsertRequest request) {
        CouponValues value = validateCoupon(request);
        requireUnique("bf_coupon", "coupon_code", value.code(), null);
        long operator = ensureOperator(principal);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_coupon
                    (coupon_code,name,coupon_type,discount_amount_cent,discount_rate,threshold_amount_cent,
                     applicable_resource_type,issue_total,per_user_limit,stackable,start_at,end_at,status)
                    VALUES (?,?,?,?,?,?,'CULTURAL_PRODUCT',?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, value.code()); statement.setString(2, value.name());
            statement.setString(3, value.type()); setLong(statement, 4, value.discountAmount());
            statement.setBigDecimal(5, value.rate()); statement.setLong(6, value.threshold());
            if (value.issueTotal() == null) statement.setNull(7, java.sql.Types.INTEGER); else statement.setInt(7, value.issueTotal());
            statement.setInt(8, value.perUserLimit()); statement.setBoolean(9, value.stackable());
            statement.setObject(10, value.startAt()); statement.setObject(11, value.endAt());
            statement.setString(12, value.status()); return statement;
        }, keys);
        long id = generatedId(keys);
        replaceCouponTargets(id, request.targets());
        audit(operator, "CULTURAL_COUPON_CREATE", "COUPON", id, value.code());
        return coupon(id);
    }

    @Transactional
    public CouponResponse updateCoupon(AuthenticatedPrincipal principal, long id, CouponUpsertRequest request) {
        CouponResponse existing = coupon(id);
        if (existing.issuedCount() > 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "优惠券已发放，不能修改规则；请停用后新建模板");
        }
        CouponValues value = validateCoupon(request);
        requireUnique("bf_coupon", "coupon_code", value.code(), id);
        long operator = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_coupon SET coupon_code=?,name=?,coupon_type=?,discount_amount_cent=?,discount_rate=?,
                       threshold_amount_cent=?,issue_total=?,per_user_limit=?,stackable=?,start_at=?,end_at=?,status=?
                WHERE id=? AND applicable_resource_type='CULTURAL_PRODUCT'
                """, value.code(), value.name(), value.type(), value.discountAmount(), value.rate(), value.threshold(),
                value.issueTotal(), value.perUserLimit(), value.stackable(), value.startAt(), value.endAt(),
                value.status(), id);
        replaceCouponTargets(id, request.targets());
        audit(operator, "CULTURAL_COUPON_UPDATE", "COUPON", id, value.code());
        return coupon(id);
    }

    @Transactional
    public List<UserCouponResponse> issueCoupon(AuthenticatedPrincipal principal, long couponId,
                                                CouponIssueRequest request) {
        if (request == null || request.userId() == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "用户ID不能为空");
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1 || quantity > 100) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "单次发放数量范围为1—100");
        long operator = ensureOperator(principal);
        requireUserId(request.userId());
        List<Long> ids = issue(couponId, request.userId(), quantity, false);
        audit(operator, "CULTURAL_COUPON_ISSUE", "COUPON", couponId, request.userId() + ":" + quantity);
        return ids.stream().map(this::userCoupon).toList();
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> claimableCoupons(AuthenticatedPrincipal principal) {
        long userId = ensureUser(principal);
        return jdbcTemplate.queryForList("""
                SELECT c.id FROM bf_coupon c
                WHERE c.applicable_resource_type='CULTURAL_PRODUCT' AND c.status='ACTIVE'
                  AND CURRENT_TIMESTAMP BETWEEN c.start_at AND c.end_at
                  AND (c.issue_total IS NULL OR (SELECT COUNT(*) FROM bf_user_coupon uc WHERE uc.coupon_id=c.id)<c.issue_total)
                  AND (SELECT COUNT(*) FROM bf_user_coupon uc WHERE uc.coupon_id=c.id AND uc.user_id=?)<c.per_user_limit
                ORDER BY c.id DESC
                """, Long.class, userId).stream().map(this::coupon).toList();
    }

    @Transactional
    public UserCouponResponse claimCoupon(AuthenticatedPrincipal principal, long couponId) {
        long userId = ensureUser(principal);
        return userCoupon(issue(couponId, userId, 1, true).get(0));
    }

    @Transactional
    public List<UserCouponResponse> myCoupons(AuthenticatedPrincipal principal, String status) {
        long userId = ensureUser(principal);
        expireUserCoupons(userId);
        String where = StringUtils.hasText(status) ? " AND uc.status=?" : "";
        Object[] args = StringUtils.hasText(status) ? new Object[]{userId, status.trim().toUpperCase(Locale.ROOT)} : new Object[]{userId};
        return jdbcTemplate.queryForList("SELECT uc.id FROM bf_user_coupon uc WHERE uc.user_id=?" + where
                + " ORDER BY uc.id DESC", Long.class, args).stream().map(this::userCoupon).toList();
    }

    @Transactional
    public PricingQuoteResponse quote(AuthenticatedPrincipal principal, PricingQuoteRequest request) {
        long userId = ensureUser(principal);
        List<PricingItem> items = loadCurrentItems(request == null ? null : request.items());
        return calculate(userId, items, request == null ? null : request.userCouponId(), false);
    }

    @Transactional
    public PricingQuoteResponse applyToOrder(AuthenticatedPrincipal principal, long orderId, Long userCouponId) {
        long userId = requireUser(principal);
        List<PricingQuoteResponse> existing = jdbcTemplate.query("""
                SELECT promotion_id,user_coupon_id,promotion_discount_cent,coupon_discount_cent,total_discount_cent,
                       (SELECT name FROM bf_cultural_promotion WHERE id=d.promotion_id) promotion_name,
                       (SELECT c.name FROM bf_user_coupon uc JOIN bf_coupon c ON c.id=uc.coupon_id WHERE uc.id=d.user_coupon_id) coupon_name,
                       o.total_amount_cent,o.payable_amount_cent
                FROM bf_cultural_order_discount d JOIN bf_order o ON o.id=d.order_id
                WHERE d.order_id=? AND o.user_id=?
                """, (rs, n) -> new PricingQuoteResponse(rs.getLong("total_amount_cent"),
                rs.getLong("promotion_discount_cent"), rs.getLong("coupon_discount_cent"),
                rs.getLong("total_discount_cent"), rs.getLong("payable_amount_cent"),
                new AppliedDiscount(rs.getObject("promotion_id", Long.class), rs.getString("promotion_name"),
                        rs.getObject("user_coupon_id", Long.class), rs.getString("coupon_name"),
                        rs.getLong("promotion_discount_cent"), rs.getLong("coupon_discount_cent"))), orderId, userId);
        if (!existing.isEmpty()) return existing.get(0);
        OrderForDiscount order = lockOrder(userId, orderId);
        if (!"CULTURAL_PRODUCT".equals(order.orderType()) || !"PENDING_PAYMENT".equals(order.status())) {
            throw new BusinessException(CommonErrorCode.ORDER_CONFLICT, "只有待支付文创订单可以计算优惠");
        }
        List<PricingItem> items = jdbcTemplate.query("""
                SELECT oi.sku_id,sku.resource_id,p.category_id,oi.unit_price_cent,oi.quantity
                FROM bf_order_item oi JOIN bf_resource_sku sku ON sku.id=oi.sku_id
                JOIN bf_cultural_product_profile p ON p.resource_id=sku.resource_id
                WHERE oi.order_id=? ORDER BY oi.id
                """, (rs, n) -> new PricingItem(rs.getLong("sku_id"), rs.getLong("resource_id"),
                rs.getLong("category_id"), rs.getLong("unit_price_cent"), rs.getInt("quantity")), orderId);
        PricingQuoteResponse quote = calculate(userId, items, userCouponId, true);
        jdbcTemplate.update("""
                UPDATE bf_order SET discount_amount_cent=?,payable_amount_cent=? WHERE id=?
                """, quote.totalDiscountCent(), quote.payableAmountCent(), orderId);
        AppliedDiscount applied = quote.applied();
        jdbcTemplate.update("""
                INSERT INTO bf_cultural_order_discount
                (order_id,promotion_id,user_coupon_id,promotion_discount_cent,coupon_discount_cent,total_discount_cent,
                 calculation_snapshot)
                VALUES (?,?,?,?,?,?,JSON_OBJECT('originalAmountCent',?,'payableAmountCent',?,
                       'promotionName',?,'couponName',?))
                """, orderId, applied.promotionId(), applied.userCouponId(), quote.promotionDiscountCent(),
                quote.couponDiscountCent(), quote.totalDiscountCent(), quote.originalAmountCent(),
                quote.payableAmountCent(), applied.promotionName(), applied.couponName());
        if (applied.userCouponId() != null) {
            int changed = jdbcTemplate.update("""
                    UPDATE bf_user_coupon SET status='USED',order_id=?,used_at=CURRENT_TIMESTAMP
                    WHERE id=? AND user_id=? AND status='UNUSED'
                    """, orderId, applied.userCouponId(), userId);
            if (changed != 1) throw new BusinessException(CommonErrorCode.CONFLICT, "优惠券状态已变化");
            jdbcTemplate.update("""
                    UPDATE bf_coupon c JOIN bf_user_coupon uc ON uc.coupon_id=c.id
                    SET c.used_count=c.used_count+1 WHERE uc.id=?
                    """, applied.userCouponId());
        }
        return quote;
    }

    @Transactional(readOnly = true)
    public PricingQuoteResponse orderPricing(long orderId, long userId) {
        List<PricingQuoteResponse> rows = jdbcTemplate.query("""
                SELECT d.promotion_id,d.user_coupon_id,d.promotion_discount_cent,d.coupon_discount_cent,
                       d.total_discount_cent,o.total_amount_cent,o.payable_amount_cent,
                       p.name promotion_name,c.name coupon_name
                FROM bf_cultural_order_discount d JOIN bf_order o ON o.id=d.order_id
                LEFT JOIN bf_cultural_promotion p ON p.id=d.promotion_id
                LEFT JOIN bf_user_coupon uc ON uc.id=d.user_coupon_id LEFT JOIN bf_coupon c ON c.id=uc.coupon_id
                WHERE d.order_id=? AND o.user_id=?
                """, (rs, n) -> new PricingQuoteResponse(rs.getLong("total_amount_cent"),
                rs.getLong("promotion_discount_cent"), rs.getLong("coupon_discount_cent"),
                rs.getLong("total_discount_cent"), rs.getLong("payable_amount_cent"),
                new AppliedDiscount(rs.getObject("promotion_id", Long.class), rs.getString("promotion_name"),
                        rs.getObject("user_coupon_id", Long.class), rs.getString("coupon_name"),
                        rs.getLong("promotion_discount_cent"), rs.getLong("coupon_discount_cent"))), orderId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void releaseCouponForCancelledOrder(long orderId) {
        List<CouponRelease> rows = jdbcTemplate.query("""
                SELECT uc.id,uc.coupon_id FROM bf_cultural_order_discount d
                JOIN bf_user_coupon uc ON uc.id=d.user_coupon_id
                WHERE d.order_id=? AND uc.order_id=? AND uc.status='USED' FOR UPDATE
                """, (rs, n) -> new CouponRelease(rs.getLong("id"), rs.getLong("coupon_id")), orderId, orderId);
        for (CouponRelease row : rows) {
            jdbcTemplate.update("""
                    UPDATE bf_user_coupon SET status=CASE WHEN (SELECT end_at FROM bf_coupon WHERE id=coupon_id)<CURRENT_TIMESTAMP
                                                         THEN 'EXPIRED' ELSE 'UNUSED' END,
                           order_id=NULL,used_at=NULL WHERE id=? AND status='USED'
                    """, row.userCouponId());
            jdbcTemplate.update("UPDATE bf_coupon SET used_count=GREATEST(used_count-1,0) WHERE id=?", row.couponId());
        }
    }

    private PricingQuoteResponse calculate(long userId, List<PricingItem> items, Long userCouponId, boolean lockCoupon) {
        long original = items.stream().mapToLong(i -> Math.multiplyExact(i.priceCent(), i.quantity())).sum();
        if (original <= 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "订单金额必须大于0");
        PromotionChoice best = bestPromotion(items);
        long promotionDiscount = best == null ? 0 : best.discount();
        CouponChoice couponChoice = userCouponId == null ? null : couponChoice(userId, userCouponId, items, lockCoupon);
        long couponDiscount = 0;
        if (couponChoice != null) {
            if (best != null && (best.exclusive() || !couponChoice.stackable())) {
                throw new BusinessException(CommonErrorCode.CONFLICT, "所选优惠券不能与当前最优活动叠加");
            }
            couponDiscount = Math.min(couponChoice.discount(), original - promotionDiscount);
        }
        // 当前支付链路不处理零元单，至少保留1分钱应付金额。
        long total = Math.min(Math.max(0, original - 1), promotionDiscount + couponDiscount);
        if (total < promotionDiscount + couponDiscount) {
            couponDiscount = Math.max(0, total - promotionDiscount);
            promotionDiscount = Math.min(promotionDiscount, total);
        }
        AppliedDiscount applied = new AppliedDiscount(best == null ? null : best.id(),
                best == null ? null : best.name(), couponChoice == null ? null : userCouponId,
                couponChoice == null ? null : couponChoice.name(), promotionDiscount, couponDiscount);
        return new PricingQuoteResponse(original, promotionDiscount, couponDiscount, total, original - total, applied);
    }

    private PromotionChoice bestPromotion(List<PricingItem> items) {
        LocalDateTime now = LocalDateTime.now();
        List<PromotionHead> active = jdbcTemplate.query("""
                SELECT id,name,promotion_type,threshold_amount_cent,reduction_amount_cent,discount_rate,priority,exclusive
                FROM bf_cultural_promotion WHERE status='ACTIVE' AND start_at<=? AND end_at>=?
                ORDER BY priority DESC,id
                """, (rs, n) -> new PromotionHead(rs.getLong("id"), rs.getString("name"),
                rs.getString("promotion_type"), rs.getLong("threshold_amount_cent"),
                rs.getObject("reduction_amount_cent", Long.class), rs.getBigDecimal("discount_rate"),
                rs.getInt("priority"), rs.getBoolean("exclusive")), now, now);
        PromotionChoice best = null;
        for (PromotionHead head : active) {
            long eligible = eligibleSubtotal(items, targets("bf_cultural_promotion_target", "promotion_id", head.id()));
            long discount = discount(head.type(), eligible, head.threshold(), head.reduction(), head.rate());
            if (discount > 0 && (best == null || discount > best.discount()
                    || discount == best.discount() && head.priority() > best.priority())) {
                best = new PromotionChoice(head.id(), head.name(), discount, head.priority(), head.exclusive());
            }
        }
        return best;
    }

    private CouponChoice couponChoice(long userId, long userCouponId, List<PricingItem> items, boolean lock) {
        expireUserCoupons(userId);
        String suffix = lock ? " FOR UPDATE" : "";
        List<CouponChoiceHead> rows = jdbcTemplate.query("""
                SELECT uc.id,c.id coupon_id,c.name,c.coupon_type,c.threshold_amount_cent,c.discount_amount_cent,
                       c.discount_rate,c.stackable,uc.status,c.status coupon_status,c.start_at,c.end_at
                FROM bf_user_coupon uc JOIN bf_coupon c ON c.id=uc.coupon_id
                WHERE uc.id=? AND uc.user_id=? AND c.applicable_resource_type='CULTURAL_PRODUCT'
                """ + suffix, (rs, n) -> new CouponChoiceHead(rs.getLong("id"), rs.getLong("coupon_id"),
                rs.getString("name"), rs.getString("coupon_type"), rs.getLong("threshold_amount_cent"),
                rs.getObject("discount_amount_cent", Long.class), rs.getBigDecimal("discount_rate"),
                rs.getBoolean("stackable"), rs.getString("status"), rs.getString("coupon_status"),
                rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class)),
                userCouponId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "优惠券不存在或不属于当前用户");
        CouponChoiceHead head = rows.get(0);
        LocalDateTime now = LocalDateTime.now();
        if (!"UNUSED".equals(head.userStatus()) || !"ACTIVE".equals(head.couponStatus())
                || now.isBefore(head.startAt()) || now.isAfter(head.endAt())) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "优惠券当前不可使用");
        }
        long eligible = eligibleSubtotal(items, targets("bf_cultural_coupon_target", "coupon_id", head.couponId()));
        long value = discount(head.type(), eligible, head.threshold(), head.amount(), head.rate());
        if (value <= 0) throw new BusinessException(CommonErrorCode.CONFLICT, "订单未达到优惠券使用条件");
        return new CouponChoice(head.name(), value, head.stackable());
    }

    private long eligibleSubtotal(List<PricingItem> items, List<TargetInput> targets) {
        boolean all = targets.isEmpty() || targets.stream().anyMatch(t -> "ALL".equals(t.targetType()));
        return items.stream().filter(item -> all || targets.stream().anyMatch(target -> switch (target.targetType()) {
            case "CATEGORY" -> target.targetId() == item.categoryId();
            case "PRODUCT" -> target.targetId() == item.productId();
            case "SKU" -> target.targetId() == item.skuId();
            default -> false;
        })).mapToLong(item -> Math.multiplyExact(item.priceCent(), item.quantity())).sum();
    }

    private long discount(String type, long eligible, long threshold, Long amount, BigDecimal rate) {
        if (eligible <= 0 || eligible < threshold) return 0;
        if ("FULL_REDUCTION".equals(type)) return Math.min(eligible, amount == null ? 0 : amount);
        long payable = BigDecimal.valueOf(eligible).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValue();
        return Math.max(0, eligible - payable);
    }

    private List<PricingItem> loadCurrentItems(List<QuoteItem> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 20) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "试算商品数量范围为1—20");
        }
        Set<Long> unique = new HashSet<>();
        List<PricingItem> result = new ArrayList<>();
        for (QuoteItem item : requested) {
            if (item == null || item.skuId() == null || item.quantity() == null || item.quantity() < 1
                    || item.quantity() > 99 || !unique.add(item.skuId())) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "试算SKU或数量不合法、存在重复SKU");
            }
            List<PricingItem> rows = jdbcTemplate.query("""
                    SELECT sku.id,sku.resource_id,p.category_id,sku.price_cent,? quantity
                    FROM bf_resource_sku sku JOIN bf_resource r ON r.id=sku.resource_id
                    JOIN bf_cultural_product_profile p ON p.resource_id=r.id
                    JOIN bf_cultural_category c ON c.id=p.category_id
                    WHERE sku.id=? AND sku.status='ACTIVE' AND r.status='ACTIVE' AND c.status='ACTIVE'
                    """, (rs, n) -> new PricingItem(rs.getLong("id"), rs.getLong("resource_id"),
                    rs.getLong("category_id"), rs.getLong("price_cent"), rs.getInt("quantity")),
                    item.quantity(), item.skuId());
            if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "试算商品不存在或已下架");
            result.add(rows.get(0));
        }
        return result;
    }

    private List<Long> issue(long couponId, long userId, int quantity, boolean requireActive) {
        CouponLock coupon = lockCoupon(couponId);
        LocalDateTime now = LocalDateTime.now();
        if (requireActive && (!"ACTIVE".equals(coupon.status()) || now.isBefore(coupon.startAt()) || now.isAfter(coupon.endAt()))) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "优惠券当前不可领取");
        }
        int issued = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_user_coupon WHERE coupon_id=?", Integer.class, couponId);
        int userIssued = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_user_coupon WHERE coupon_id=? AND user_id=?", Integer.class, couponId, userId);
        if (coupon.issueTotal() != null && issued + quantity > coupon.issueTotal()) throw new BusinessException(CommonErrorCode.CONFLICT, "优惠券已领完");
        if (userIssued + quantity > coupon.perUserLimit()) throw new BusinessException(CommonErrorCode.CONFLICT, "超过每人限领数量");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            jdbcTemplate.update("INSERT INTO bf_user_coupon(coupon_id,user_id,status) VALUES (?,?,'UNUSED')", couponId, userId);
            ids.add(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        }
        return ids;
    }

    private CouponLock lockCoupon(long id) {
        List<CouponLock> rows = jdbcTemplate.query("""
                SELECT id,issue_total,per_user_limit,start_at,end_at,status FROM bf_coupon
                WHERE id=? AND applicable_resource_type='CULTURAL_PRODUCT' FOR UPDATE
                """, (rs, n) -> new CouponLock(rs.getLong("id"), rs.getObject("issue_total", Integer.class),
                rs.getInt("per_user_limit"), rs.getObject("start_at", LocalDateTime.class),
                rs.getObject("end_at", LocalDateTime.class), rs.getString("status")), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创优惠券不存在");
        return rows.get(0);
    }

    private void expireUserCoupons(long userId) {
        jdbcTemplate.update("""
                UPDATE bf_user_coupon uc JOIN bf_coupon c ON c.id=uc.coupon_id
                SET uc.status='EXPIRED' WHERE uc.user_id=? AND uc.status='UNUSED' AND c.end_at<CURRENT_TIMESTAMP
                """, userId);
    }

    private PromotionResponse promotion(long id) {
        List<PromotionResponse> rows = jdbcTemplate.query("""
                SELECT id,promotion_code,name,promotion_type,threshold_amount_cent,reduction_amount_cent,
                       discount_rate,priority,exclusive,start_at,end_at,status
                FROM bf_cultural_promotion WHERE id=?
                """, (rs, n) -> new PromotionResponse(rs.getLong("id"), rs.getString("promotion_code"),
                rs.getString("name"), rs.getString("promotion_type"), rs.getLong("threshold_amount_cent"),
                rs.getObject("reduction_amount_cent", Long.class), rs.getBigDecimal("discount_rate"),
                rs.getInt("priority"), rs.getBoolean("exclusive"), rs.getObject("start_at", LocalDateTime.class),
                rs.getObject("end_at", LocalDateTime.class), rs.getString("status"),
                targets("bf_cultural_promotion_target", "promotion_id", id)), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创促销活动不存在");
        return rows.get(0);
    }

    private CouponResponse coupon(long id) {
        List<CouponResponse> rows = jdbcTemplate.query("""
                SELECT c.id,c.coupon_code,c.name,c.coupon_type,c.threshold_amount_cent,c.discount_amount_cent,
                       c.discount_rate,c.issue_total,(SELECT COUNT(*) FROM bf_user_coupon uc WHERE uc.coupon_id=c.id) issued_count,
                       c.used_count,c.per_user_limit,c.stackable,c.start_at,c.end_at,c.status
                FROM bf_coupon c WHERE c.id=? AND c.applicable_resource_type='CULTURAL_PRODUCT'
                """, (rs, n) -> new CouponResponse(rs.getLong("id"), rs.getString("coupon_code"), rs.getString("name"),
                rs.getString("coupon_type"), rs.getLong("threshold_amount_cent"),
                rs.getObject("discount_amount_cent", Long.class), rs.getBigDecimal("discount_rate"),
                rs.getObject("issue_total", Integer.class), rs.getInt("issued_count"), rs.getInt("used_count"),
                rs.getInt("per_user_limit"), rs.getBoolean("stackable"),
                rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class),
                rs.getString("status"), targets("bf_cultural_coupon_target", "coupon_id", id)), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创优惠券不存在");
        return rows.get(0);
    }

    private UserCouponResponse userCoupon(long id) {
        return jdbcTemplate.queryForObject("""
                SELECT uc.id,c.id coupon_id,c.coupon_code,c.name,c.coupon_type,c.threshold_amount_cent,
                       c.discount_amount_cent,c.discount_rate,c.stackable,uc.status,c.start_at,c.end_at,
                       uc.received_at,uc.used_at,uc.order_id
                FROM bf_user_coupon uc JOIN bf_coupon c ON c.id=uc.coupon_id WHERE uc.id=?
                """, (rs, n) -> new UserCouponResponse(rs.getLong("id"), rs.getLong("coupon_id"),
                rs.getString("coupon_code"), rs.getString("name"), rs.getString("coupon_type"),
                rs.getLong("threshold_amount_cent"), rs.getObject("discount_amount_cent", Long.class),
                rs.getBigDecimal("discount_rate"), rs.getBoolean("stackable"), rs.getString("status"),
                rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class),
                rs.getObject("received_at", LocalDateTime.class), rs.getObject("used_at", LocalDateTime.class),
                rs.getObject("order_id", Long.class)), id);
    }

    private List<TargetInput> targets(String table, String ownerColumn, long ownerId) {
        return jdbcTemplate.query("SELECT target_type,target_id FROM " + table + " WHERE " + ownerColumn + "=? ORDER BY id",
                (rs, n) -> new TargetInput(rs.getString("target_type"), rs.getLong("target_id")), ownerId);
    }
    private void replacePromotionTargets(long id, List<TargetInput> targets) { replaceTargets("bf_cultural_promotion_target", "promotion_id", id, targets); }
    private void replaceCouponTargets(long id, List<TargetInput> targets) { replaceTargets("bf_cultural_coupon_target", "coupon_id", id, targets); }
    private void replaceTargets(String table, String ownerColumn, long ownerId, List<TargetInput> requested) {
        jdbcTemplate.update("DELETE FROM " + table + " WHERE " + ownerColumn + "=?", ownerId);
        List<TargetInput> values = requested == null || requested.isEmpty() ? List.of(new TargetInput("ALL", 0L)) : requested;
        if (values.size() > 100) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "适用范围最多100项");
        Set<String> unique = new HashSet<>();
        for (TargetInput target : values) {
            String type = normalizeTargetType(target.targetType());
            long targetId = "ALL".equals(type) ? 0 : target.targetId() == null ? 0 : target.targetId();
            if (!"ALL".equals(type) && targetId <= 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "适用范围ID不合法");
            if (!unique.add(type + ":" + targetId)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "适用范围重复");
            validateTarget(type, targetId);
            jdbcTemplate.update("INSERT INTO " + table + "(" + ownerColumn + ",target_type,target_id) VALUES (?,?,?)",
                    ownerId, type, targetId);
        }
    }
    private void validateTarget(String type, long id) {
        if ("ALL".equals(type)) return;
        String sql = switch (type) {
            case "CATEGORY" -> "SELECT COUNT(*) FROM bf_cultural_category WHERE id=?";
            case "PRODUCT" -> "SELECT COUNT(*) FROM bf_cultural_product_profile WHERE resource_id=?";
            case "SKU" -> "SELECT COUNT(*) FROM bf_cultural_stock WHERE sku_id=?";
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "适用范围类型不合法");
        };
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, id);
        if (count == null || count == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "适用范围目标不存在");
    }

    private PromotionValues validatePromotion(PromotionUpsertRequest request) {
        if (request == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "促销活动不能为空");
        RuleValues rule = validateRule(request.promotionType(), request.thresholdAmountCent(),
                request.reductionAmountCent(), request.discountRate());
        validateTime(request.startAt(), request.endAt());
        return new PromotionValues(code(request.promotionCode()), name(request.name()), rule.type(), rule.threshold(),
                rule.amount(), rule.rate(), request.priority() == null ? 0 : request.priority(),
                Boolean.TRUE.equals(request.exclusive()), request.startAt(), request.endAt(), status(request.status()));
    }
    private CouponValues validateCoupon(CouponUpsertRequest request) {
        if (request == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "优惠券不能为空");
        RuleValues rule = validateRule(request.couponType(), request.thresholdAmountCent(),
                request.discountAmountCent(), request.discountRate());
        validateTime(request.startAt(), request.endAt());
        int limit = request.perUserLimit() == null ? 1 : request.perUserLimit();
        if (limit < 1 || limit > 100 || request.issueTotal() != null && request.issueTotal() < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "发行总量或每人限领数量不合法");
        }
        return new CouponValues(code(request.couponCode()), name(request.name()), rule.type(), rule.threshold(),
                rule.amount(), rule.rate(), request.issueTotal(), limit, Boolean.TRUE.equals(request.stackable()),
                request.startAt(), request.endAt(), status(request.status()));
    }
    private RuleValues validateRule(String typeValue, Long thresholdValue, Long amount, BigDecimal rate) {
        String type = typeValue == null ? "" : typeValue.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "优惠类型不合法");
        long threshold = thresholdValue == null ? 0 : thresholdValue;
        if (threshold < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "使用门槛不能为负数");
        if ("FULL_REDUCTION".equals(type) && (amount == null || amount <= 0)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "满减金额必须大于0");
        if ("DISCOUNT".equals(type) && (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.ONE) >= 0)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "折扣率必须大于0且小于1");
        return new RuleValues(type, threshold, "FULL_REDUCTION".equals(type) ? amount : null,
                "DISCOUNT".equals(type) ? rate : null);
    }
    private void validateTime(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "活动起止时间不合法");
    }
    private String status(String value) {
        String result = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "DRAFT";
        if (!STATUSES.contains(result)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "状态不合法");
        return result;
    }
    private String normalizeTargetType(String value) {
        String result = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "ALL";
        if (!TARGET_TYPES.contains(result)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "适用范围类型不合法");
        return result;
    }
    private String code(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() > 50) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "编码不能为空且最多50字符");
        return value.trim().toUpperCase(Locale.ROOT);
    }
    private String name(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() > 100) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "名称不能为空且最多100字符");
        return value.trim();
    }
    private void requireUnique(String table, String column, String value, Long excludeId) {
        String sql = "SELECT id FROM " + table + " WHERE " + column + "=?" + (excludeId == null ? "" : " AND id<>?");
        List<Long> ids = excludeId == null ? jdbcTemplate.queryForList(sql, Long.class, value)
                : jdbcTemplate.queryForList(sql, Long.class, value, excludeId);
        if (!ids.isEmpty()) throw new BusinessException(CommonErrorCode.CONFLICT, "编码已存在");
    }
    private OrderForDiscount lockOrder(long userId, long orderId) {
        List<OrderForDiscount> rows = jdbcTemplate.query("""
                SELECT id,order_type,status FROM bf_order WHERE id=? AND user_id=? FOR UPDATE
                """, (rs, n) -> new OrderForDiscount(rs.getLong("id"), rs.getString("order_type"),
                rs.getString("status")), orderId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "订单不存在或无权访问");
        return rows.get(0);
    }
    private long requireUser(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, principal.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户尚未建立商城资料");
        return ids.get(0);
    }
    private void requireUserId(long userId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_user WHERE id=?", Integer.class, userId);
        if (count == null || count == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "用户不存在");
    }
    private long ensureOperator(AuthenticatedPrincipal principal) {
        return ensureUser(principal);
    }
    private long ensureUser(AuthenticatedPrincipal principal) {
        jdbcTemplate.update("""
                INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE')
                ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)
                """, principal.databaseOpenId(), principal.displayName());
        return requireUser(principal);
    }
    private void audit(long operatorId, String action, String targetType, long targetId, String value) {
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?,?,?,JSON_OBJECT('value',?))
                """, operatorId, action, targetType, String.valueOf(targetId), value);
    }
    private long generatedId(KeyHolder keys) {
        if (keys.getKey() == null) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "数据创建失败");
        return keys.getKey().longValue();
    }
    private void setLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT); else statement.setLong(index, value);
    }
    private void bindPromotion(PreparedStatement statement, PromotionValues v) throws java.sql.SQLException {
        statement.setString(1, v.code()); statement.setString(2, v.name()); statement.setString(3, v.type());
        statement.setLong(4, v.threshold()); setLong(statement, 5, v.reduction()); statement.setBigDecimal(6, v.rate());
        statement.setInt(7, v.priority()); statement.setBoolean(8, v.exclusive());
        statement.setObject(9, v.startAt()); statement.setObject(10, v.endAt()); statement.setString(11, v.status());
    }

    private record PromotionValues(String code,String name,String type,long threshold,Long reduction,BigDecimal rate,
                                   int priority,boolean exclusive,LocalDateTime startAt,LocalDateTime endAt,String status) {}
    private record CouponValues(String code,String name,String type,long threshold,Long discountAmount,BigDecimal rate,
                                Integer issueTotal,int perUserLimit,boolean stackable,LocalDateTime startAt,
                                LocalDateTime endAt,String status) {}
    private record RuleValues(String type,long threshold,Long amount,BigDecimal rate) {}
    private record PricingItem(long skuId,long productId,long categoryId,long priceCent,int quantity) {}
    private record PromotionHead(long id,String name,String type,long threshold,Long reduction,BigDecimal rate,
                                 int priority,boolean exclusive) {}
    private record PromotionChoice(long id,String name,long discount,int priority,boolean exclusive) {}
    private record CouponChoiceHead(long id,long couponId,String name,String type,long threshold,Long amount,
                                    BigDecimal rate,boolean stackable,String userStatus,String couponStatus,
                                    LocalDateTime startAt,LocalDateTime endAt) {}
    private record CouponChoice(String name,long discount,boolean stackable) {}
    private record CouponLock(long id,Integer issueTotal,int perUserLimit,LocalDateTime startAt,
                              LocalDateTime endAt,String status) {}
    private record OrderForDiscount(long id,String orderType,String status) {}
    private record CouponRelease(long userCouponId,long couponId) {}
}
