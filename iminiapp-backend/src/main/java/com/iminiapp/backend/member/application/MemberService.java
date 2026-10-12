package com.iminiapp.backend.member.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.member.api.MemberModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MemberService {
    private final JdbcTemplate jdbc;

    public MemberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public MemberProfileResponse mine(AuthenticatedPrincipal principal) {
        long accountId = ensureAccount(principal);
        checkUpgrade(accountId);
        return profile(accountId);
    }

    @Transactional
    public MemberProfileResponse checkUpgrade(AuthenticatedPrincipal principal) {
        long accountId = ensureAccount(principal);
        checkUpgrade(accountId);
        return profile(accountId);
    }

    @Transactional
    public MemberProfileResponse changePoints(AuthenticatedPrincipal principal, PointOperationRequest request) {
        if (request == null || !StringUtils.hasText(request.action()) || !StringUtils.hasText(request.orderId())) {
            throw invalid("积分操作类型和订单号不能为空");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if (!List.of("REGISTER", "SETTLE", "CANCEL", "REVERSE").contains(action)) {
            throw invalid("不支持的积分操作");
        }
        String orderRef = limited(request.orderId(), 64, "订单号");
        String description = optional(request.description(), 255);
        long amount = request.paidAmountFen() == null ? 0 : Math.max(0, request.paidAmountFen());
        int earned = (int)Math.min(Integer.MAX_VALUE, amount / 100);
        long accountId = ensureAccount(principal);
        Account account = lockAccount(accountId);
        PointOrder existing = pointOrder(accountId, orderRef);

        switch (action) {
            case "REGISTER" -> register(accountId, orderRef, amount, earned, description, existing);
            case "SETTLE" -> settle(account, orderRef, amount, earned, description, existing);
            case "CANCEL" -> cancel(accountId, orderRef, amount, earned, description, existing);
            case "REVERSE" -> reverse(account, orderRef, amount, earned, description, existing);
            default -> throw invalid("不支持的积分操作");
        }
        checkUpgrade(accountId);
        return profile(accountId);
    }

    @Transactional
    public MemberProfileResponse recharge(AuthenticatedPrincipal principal, RechargeRequest request) {
        if (request == null || request.amountFen() == null || request.amountFen() < 1 || request.amountFen() > 10_000_000L) {
            throw invalid("单次充值金额范围为1分至10万元");
        }
        String key = StringUtils.hasText(request.clientRequestId())
                ? limited(request.clientRequestId(), 64, "请求编号") : UUID.randomUUID().toString();
        long accountId = ensureAccount(principal);
        Account account = lockAccount(accountId);
        String idempotency = "MEMBER_RECHARGE:" + key;
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM bf_sport_wallet_transaction WHERE member_account_id=? AND idempotency_key=?",
                Integer.class, accountId, idempotency);
        if (exists != null && exists > 0) return profile(accountId);
        long after;
        try { after = Math.addExact(account.balanceFen(), request.amountFen()); }
        catch (ArithmeticException error) { throw invalid("充值金额超出系统范围"); }
        if (jdbc.update("UPDATE bf_member_account SET balance_cent=?,version=version+1 WHERE id=? AND version=?",
                after, accountId, account.version()) != 1) throw invalid("会员余额已变化，请重试");
        String no = "MR" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO bf_sport_wallet_transaction
                (member_account_id,transaction_no,transaction_type,amount_cent,balance_before_cent,balance_after_cent,idempotency_key,remark)
                VALUES (?,?,'RECHARGE',?,?,?,?,'小程序会员余额充值')
                """, accountId, no, request.amountFen(), account.balanceFen(), after, idempotency);
        return profile(accountId);
    }

    private void register(long accountId, String orderRef, long amount, int earned, String description, PointOrder existing) {
        if (existing != null) return;
        insertPointOrder(accountId, orderRef, amount, earned, description, "PENDING");
    }

    private void settle(Account account, String orderRef, long amount, int earned, String description, PointOrder existing) {
        if (existing != null && ("SETTLED".equals(existing.status()) || "REVERSED".equals(existing.status()))) return;
        if (existing != null && "CANCELLED".equals(existing.status())) return;
        int points = existing == null ? earned : existing.points();
        if (existing == null) insertPointOrder(account.id(), orderRef, amount, points, description, "SETTLED");
        else jdbc.update("UPDATE bf_member_point_order SET status='SETTLED',settled_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", existing.id());
        if (points == 0) return;
        int afterPoints = Math.addExact(account.points(), points);
        int afterGrowth = Math.addExact(account.growth(), points);
        jdbc.update("UPDATE bf_member_account SET points=?,growth_value=?,version=version+1 WHERE id=?",
                afterPoints, afterGrowth, account.id());
        insertPointTransaction(account.id(), orderRef, "EARN", points, afterPoints, afterGrowth, description);
    }

    private void cancel(long accountId, String orderRef, long amount, int earned, String description, PointOrder existing) {
        if (existing == null) insertPointOrder(accountId, orderRef, amount, earned, description, "CANCELLED");
        else if ("PENDING".equals(existing.status())) jdbc.update("UPDATE bf_member_point_order SET status='CANCELLED' WHERE id=?", existing.id());
    }

    private void reverse(Account account, String orderRef, long amount, int earned, String description, PointOrder existing) {
        if (existing == null) {
            insertPointOrder(account.id(), orderRef, amount, earned, description, "CANCELLED");
            return;
        }
        if ("PENDING".equals(existing.status())) {
            jdbc.update("UPDATE bf_member_point_order SET status='CANCELLED' WHERE id=?", existing.id());
            return;
        }
        if (!"SETTLED".equals(existing.status())) return;
        int afterPoints = Math.max(0, account.points() - existing.points());
        int afterGrowth = Math.max(0, account.growth() - existing.points());
        jdbc.update("UPDATE bf_member_point_order SET status='REVERSED',reversed_at=CURRENT_TIMESTAMP WHERE id=? AND status='SETTLED'", existing.id());
        jdbc.update("UPDATE bf_member_account SET points=?,growth_value=?,version=version+1 WHERE id=?",
                afterPoints, afterGrowth, account.id());
        insertPointTransaction(account.id(), orderRef, "REVERSE", -existing.points(), afterPoints, afterGrowth, description);
    }

    private long ensureAccount(AuthenticatedPrincipal principal) {
        jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",
                principal.databaseOpenId(), StringUtils.hasText(principal.displayName()) ? principal.displayName() : "微信用户");
        List<Long> activeUsers = jdbc.queryForList(
                "SELECT id FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'", Long.class,
                principal.databaseOpenId());
        if (activeUsers.isEmpty()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "账号已停用，不能初始化会员账户");
        }
        Long userId = activeUsers.get(0);
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_member_account WHERE user_id=?", Long.class, userId);
        if (ids.isEmpty()) {
            try {
                jdbc.update("INSERT INTO bf_member_account(member_no,user_id,level_code,status,registered_at) VALUES (?,?,'NORMAL','ACTIVE',CURRENT_TIMESTAMP)",
                        "BZ" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT), userId);
            } catch (DuplicateKeyException ignored) {}
        }
        return jdbc.queryForObject("SELECT id FROM bf_member_account WHERE user_id=?", Long.class, userId);
    }

    private void checkUpgrade(long accountId) {
        Account account = lockAccount(accountId);
        if (account.validUntil() != null && account.validUntil().isBefore(LocalDate.now())) {
            jdbc.update("UPDATE bf_member_account SET level_code='NORMAL',growth_value=0,valid_until=NULL,version=version+1 WHERE id=?", accountId);
            account = lockAccount(accountId);
        }
        Level level = jdbc.queryForObject("""
                SELECT level_code,level_name,sport_discount_bp,min_growth_value
                FROM bf_sport_member_level WHERE status='ACTIVE' AND min_growth_value<=?
                ORDER BY min_growth_value DESC,id DESC LIMIT 1
                """, (rs, n) -> new Level(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)), account.growth());
        if (level != null && !level.code().equals(account.levelCode())) {
            LocalDate validUntil = "NORMAL".equals(level.code()) ? null : LocalDate.now().plusYears(1);
            jdbc.update("UPDATE bf_member_account SET level_code=?,valid_until=?,version=version+1 WHERE id=?",
                    level.code(), validUntil, accountId);
        }
    }

    private MemberProfileResponse profile(long accountId) {
        return jdbc.queryForObject("""
                SELECT m.*,u.nickname,u.avatar_url,l.level_name,l.sport_discount_bp
                FROM bf_member_account m JOIN bf_user u ON u.id=m.user_id
                JOIN bf_sport_member_level l ON l.level_code=m.level_code WHERE m.id=?
                """, (rs, n) -> {
            String nickname = rs.getString("nickname");
            String code = rs.getString("level_code");
            int bp = rs.getInt("sport_discount_bp");
            List<String> rewarded = jdbc.queryForList("SELECT order_ref FROM bf_member_point_order WHERE member_account_id=? AND status='SETTLED' ORDER BY id DESC LIMIT 100", String.class, accountId);
            List<PendingPointOrder> pending = jdbc.query("SELECT * FROM bf_member_point_order WHERE member_account_id=? AND status='PENDING' ORDER BY id DESC LIMIT 100",
                    (p, i) -> new PendingPointOrder(p.getString("order_ref"), p.getLong("paid_amount_cent"), p.getInt("points"), p.getString("description"), p.getObject("registered_at", LocalDateTime.class)), accountId);
            List<PointRecord> records = jdbc.query("SELECT * FROM bf_member_point_transaction WHERE member_account_id=? ORDER BY id DESC LIMIT 30",
                    (p, i) -> new PointRecord(p.getString("order_ref"), "REVERSE".equals(p.getString("transaction_type")) ? p.getString("order_ref") : null,
                            p.getInt("points_delta"), p.getString("description"), p.getObject("created_at", LocalDateTime.class)), accountId);
            LocalDate until = rs.getObject("valid_until", LocalDate.class);
            return new MemberProfileResponse(nickname, first(nickname), rs.getString("avatar_url"), rs.getString("member_no"),
                    rs.getInt("points"), rs.getInt("growth_value"), rs.getLong("balance_cent"), 0,
                    code, rs.getString("level_name"), badge(code), bp / 10000D, discount(bp),
                    until == null ? null : until.toString(), rewarded, pending, records);
        }, accountId);
    }

    private Account lockAccount(long id) {
        return jdbc.queryForObject("SELECT id,balance_cent,points,growth_value,level_code,valid_until,version FROM bf_member_account WHERE id=? FOR UPDATE",
                (rs, n) -> new Account(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getObject(6, LocalDate.class), rs.getInt(7)), id);
    }

    private PointOrder pointOrder(long accountId, String orderRef) {
        List<PointOrder> rows = jdbc.query("SELECT id,points,status FROM bf_member_point_order WHERE member_account_id=? AND order_ref=? FOR UPDATE",
                (rs, n) -> new PointOrder(rs.getLong(1), rs.getInt(2), rs.getString(3)), accountId, orderRef);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertPointOrder(long accountId, String orderRef, long amount, int points, String description, String status) {
        jdbc.update("INSERT INTO bf_member_point_order(member_account_id,order_ref,paid_amount_cent,points,description,status,settled_at,reversed_at) VALUES (?,?,?,?,?,?,IF(?='SETTLED',CURRENT_TIMESTAMP,NULL),IF(?='REVERSED',CURRENT_TIMESTAMP,NULL))",
                accountId, orderRef, amount, points, description, status, status, status);
    }

    private void insertPointTransaction(long accountId, String orderRef, String type, int delta,
                                        int pointsAfter, int growthAfter, String description) {
        jdbc.update("INSERT IGNORE INTO bf_member_point_transaction(member_account_id,order_ref,transaction_type,points_delta,points_after,growth_after,description) VALUES (?,?,?,?,?,?,?)",
                accountId, orderRef, type, delta, pointsAfter, growthAfter, description);
    }

    private String badge(String code) { return switch (code) { case "SILVER" -> "银"; case "GOLD" -> "金"; case "PLATINUM" -> "铂"; case "DIAMOND" -> "钻"; case "BLACK_GOLD" -> "黑"; default -> "普"; }; }
    private String discount(int bp) { return bp == 10000 ? "无折扣" : (bp % 1000 == 0 ? (bp / 1000) + "折" : (bp / 100D) + "折"); }
    private String first(String value) { return StringUtils.hasText(value) ? value.substring(0, 1) : "北"; }
    private String limited(String value, int max, String name) { String v = value.trim(); if (v.length() > max) throw invalid(name + "最长" + max + "字符"); return v; }
    private String optional(String value, int max) { return StringUtils.hasText(value) ? limited(value, max, "说明") : null; }
    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }
    private record Account(long id, long balanceFen, int points, int growth, String levelCode, LocalDate validUntil, int version) {}
    private record PointOrder(long id, int points, String status) {}
    private record Level(String code, String name, int discountBp, int minGrowth) {}
}
