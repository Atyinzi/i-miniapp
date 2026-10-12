package com.iminiapp.backend.sport.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.api.PaymentResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.sport.api.SportMemberModels.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SportMemberService {
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SportMemberService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MemberResponse register(AuthenticatedPrincipal principal) {
        long userId = ensureUser(principal);
        List<Long> existing = jdbc.queryForList("SELECT id FROM bf_member_account WHERE user_id=?", Long.class, userId);
        if (existing.isEmpty()) {
            try {
                jdbc.update("""
                        INSERT INTO bf_member_account(member_no,user_id,level_code,status,registered_at)
                        VALUES (?,?,'NORMAL','ACTIVE',CURRENT_TIMESTAMP)
                        """, nextNo("SM"), userId);
            } catch (DuplicateKeyException ignored) {
                // 同一用户并发注册时由唯一约束收敛为同一个会员账户。
            }
        }
        return memberByUser(userId);
    }

    @Transactional(readOnly = true)
    public MemberResponse mine(AuthenticatedPrincipal principal) {
        return memberByUser(requireUser(principal));
    }

    @Transactional
    public WalletTransactionResponse recharge(AuthenticatedPrincipal principal, String idempotencyKey,
                                               RechargeRequest request) {
        validateKey(idempotencyKey);
        if (request == null || request.amountCent() == null || request.amountCent() < 1 || request.amountCent() > 10_000_000L)
            throw invalid("单次充值金额范围为1分至10万元");
        Account account = lockAccount(requireUser(principal));
        WalletTransactionResponse existing = transactionByKey(account.id(), "RECHARGE:" + idempotencyKey);
        if (existing != null) return existing;
        return changeBalance(account, request.amountCent(), "RECHARGE", null, null,
                "RECHARGE:" + idempotencyKey, optional(request.remark(), 255));
    }

    @Transactional
    public PaymentResponse balancePay(AuthenticatedPrincipal principal, long orderId, String idempotencyKey) {
        validateKey(idempotencyKey);
        long userId = requireUser(principal);
        Order order = lockOrder(userId, orderId);
        PaymentResponse paid = successfulPayment(orderId);
        if (paid != null) return paid;
        if (!"SPORT_VENUE".equals(order.orderType())) throw conflict("会员余额目前仅支持支付体育场地订单");
        if (!"PENDING_PAYMENT".equals(order.status())) throw conflict("只有待支付订单可以使用会员余额支付");
        if (order.expiresAt() != null && order.expiresAt().isBefore(LocalDateTime.now())) throw conflict("订单已超过支付截止时间");

        Account account = lockAccount(userId);
        String walletKey = "ORDER_PAY:" + orderId;
        WalletTransactionResponse existing = transactionByKey(account.id(), walletKey);
        if (existing != null) {
            PaymentResponse result = successfulPayment(orderId);
            if (result != null) return result;
            throw conflict("余额扣款结果与订单状态不一致，请联系管理员");
        }
        changeBalance(account, -order.payableAmountCent(), "CONSUME", orderId, order.orderNo(), walletKey, "体育场地预订余额支付");

        String paymentNo = nextNo("BAL");
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bf_payment(order_id,payment_no,channel,transaction_id,amount_cent,status,paid_at,callback_payload)
                    VALUES (?,?,'BALANCE',?,?,'SUCCESS',CURRENT_TIMESTAMP,JSON_OBJECT('source','SPORT_MEMBER_WALLET','verified',TRUE))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId); ps.setString(2, paymentNo);
            ps.setString(3, "BALANCE:" + account.id() + ":" + orderId); ps.setLong(4, order.payableAmountCent());
            return ps;
        }, keys);
        if (jdbc.update("UPDATE bf_order SET status='PAID',paid_amount_cent=payable_amount_cent WHERE id=? AND status='PENDING_PAYMENT'", orderId) != 1)
            throw conflict("订单状态已变化，请刷新后重试");
        jdbc.update("UPDATE bf_sport_booking SET status='CONFIRMED',confirmed_at=COALESCE(confirmed_at,CURRENT_TIMESTAMP) WHERE order_id=? AND status='HELD'", orderId);
        jdbc.update("""
                INSERT IGNORE INTO bf_sport_booking_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                SELECT id,'HELD','CONFIRMED',?,'会员余额支付成功',CONCAT('SPORT-BALANCE-PAY:',id)
                FROM bf_sport_booking WHERE order_id=?
                """, userId, orderId);
        jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,'SPORT_BALANCE_PAY','ORDER',?,JSON_OBJECT('paymentNo',?,'amountCent',?))",
                userId, String.valueOf(orderId), paymentNo, order.payableAmountCent());
        return payment(keys.getKey().longValue());
    }

    @Transactional(readOnly = true)
    public PageResponse<WalletTransactionResponse> transactions(AuthenticatedPrincipal principal, int page, int pageSize) {
        validatePage(page, pageSize);
        Account account = account(requireUser(principal), false);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM bf_sport_wallet_transaction WHERE member_account_id=?", Long.class, account.id());
        List<WalletTransactionResponse> items = jdbc.query("""
                SELECT * FROM bf_sport_wallet_transaction WHERE member_account_id=?
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> transaction(rs), account.id(), pageSize, (page - 1) * pageSize);
        long count = total == null ? 0 : total;
        return new PageResponse<>(items, page, pageSize, count, count == 0 ? 0 : (int)((count + pageSize - 1) / pageSize));
    }

    @Transactional
    public void applyDiscount(long orderId) {
        List<DiscountData> rows = jdbc.query("""
                SELECT b.id booking_id,b.price_cent,o.user_id,m.id member_id,m.level_code,l.sport_discount_bp
                FROM bf_sport_booking b JOIN bf_order o ON o.id=b.order_id
                JOIN bf_member_account m ON m.user_id=o.user_id AND m.status='ACTIVE'
                JOIN bf_sport_member_level l ON l.level_code=m.level_code AND l.status='ACTIVE'
                WHERE b.order_id=? AND o.status='PENDING_PAYMENT' AND b.member_account_id IS NULL FOR UPDATE
                """, (rs, n) -> new DiscountData(rs.getLong("booking_id"), rs.getLong("price_cent"),
                rs.getLong("user_id"), rs.getLong("member_id"), rs.getString("level_code"), rs.getInt("sport_discount_bp")), orderId);
        if (rows.isEmpty()) return;
        DiscountData data = rows.get(0);
        long payable = (data.originalCent() * data.discountBp() + 9999L) / 10000L;
        long discount = data.originalCent() - payable;
        jdbc.update("UPDATE bf_order SET total_amount_cent=?,discount_amount_cent=?,payable_amount_cent=? WHERE id=? AND status='PENDING_PAYMENT'",
                data.originalCent(), discount, payable, orderId);
        jdbc.update("UPDATE bf_order_item SET unit_price_cent=?,amount_cent=?,snapshot=JSON_SET(snapshot,'$.memberLevel',?,'$.originalPriceCent',?) WHERE order_id=?",
                payable, payable, data.levelCode(), data.originalCent(), orderId);
        jdbc.update("""
                UPDATE bf_sport_booking SET original_price_cent=?,price_cent=?,member_discount_cent=?,
                member_account_id=?,member_level_code=? WHERE id=?
                """, data.originalCent(), payable, discount, data.memberId(), data.levelCode(), data.bookingId());
    }

    @Transactional(readOnly = true)
    public List<MemberLevelResponse> levels(String status) {
        String sql = "SELECT * FROM bf_sport_member_level" + (StringUtils.hasText(status) ? " WHERE status=?" : "") + " ORDER BY min_growth_value,id";
        return StringUtils.hasText(status) ? jdbc.query(sql, (rs,n)->level(rs), status.trim().toUpperCase(Locale.ROOT)) : jdbc.query(sql, (rs,n)->level(rs));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMemberResponse> adminMembers(String levelCode, String status, int page, int pageSize) {
        validatePage(page,pageSize); StringBuilder where=new StringBuilder(" WHERE 1=1"); java.util.ArrayList<Object> args=new java.util.ArrayList<>();
        if(StringUtils.hasText(levelCode)){where.append(" AND m.level_code=?");args.add(levelCode.trim().toUpperCase(Locale.ROOT));}
        if(StringUtils.hasText(status)){where.append(" AND m.status=?");args.add(status.trim().toUpperCase(Locale.ROOT));}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM bf_member_account m"+where,Long.class,args.toArray());
        args.add(pageSize);args.add((page-1)*pageSize);
        List<AdminMemberResponse> items=jdbc.query("""
                SELECT m.id,m.member_no,m.user_id,u.nickname,u.mobile,m.level_code,l.level_name,l.sport_discount_bp,
                m.status,m.balance_cent,m.points,m.growth_value,m.registered_at
                FROM bf_member_account m JOIN bf_user u ON u.id=m.user_id
                JOIN bf_sport_member_level l ON l.level_code=m.level_code
                """+where+" ORDER BY m.id DESC LIMIT ? OFFSET ?",(rs,n)->new AdminMemberResponse(
                rs.getLong("id"),rs.getString("member_no"),rs.getLong("user_id"),rs.getString("nickname"),
                rs.getString("mobile"),rs.getString("level_code"),rs.getString("level_name"),
                rs.getInt("sport_discount_bp"),rs.getString("status"),rs.getLong("balance_cent"),
                rs.getInt("points"),rs.getInt("growth_value"),rs.getObject("registered_at",LocalDateTime.class)),args.toArray());
        long count=total==null?0:total;return new PageResponse<>(items,page,pageSize,count,count==0?0:(int)((count+pageSize-1)/pageSize));
    }

    @Transactional(readOnly = true)
    public PageResponse<WalletTransactionResponse> adminTransactions(long memberId, int page, int pageSize) {
        validatePage(page,pageSize);
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM bf_member_account WHERE id=?",Integer.class,memberId);
        if(exists==null||exists==0)throw new BusinessException(CommonErrorCode.NOT_FOUND,"会员不存在");
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM bf_sport_wallet_transaction WHERE member_account_id=?",Long.class,memberId);
        List<WalletTransactionResponse> items=jdbc.query("SELECT * FROM bf_sport_wallet_transaction WHERE member_account_id=? ORDER BY id DESC LIMIT ? OFFSET ?",(rs,n)->transaction(rs),memberId,pageSize,(page-1)*pageSize);
        long count=total==null?0:total;return new PageResponse<>(items,page,pageSize,count,count==0?0:(int)((count+pageSize-1)/pageSize));
    }

    @Transactional
    public MemberLevelResponse createLevel(AuthenticatedPrincipal principal, MemberLevelUpsertRequest request) {
        LevelInput in = validateLevel(request); long operator = ensureUser(principal);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(c -> { PreparedStatement ps=c.prepareStatement("""
                INSERT INTO bf_sport_member_level(level_code,level_name,sport_discount_bp,min_growth_value,benefits,status)
                VALUES (?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1,in.code()); ps.setString(2,in.name()); ps.setInt(3,in.discountBp()); ps.setInt(4,in.minGrowth());
            ps.setString(5,json(in.benefits())); ps.setString(6,in.status()); return ps; }, keys);
        audit(operator,"SPORT_MEMBER_LEVEL_CREATE",keys.getKey().longValue());
        return levelById(keys.getKey().longValue());
    }

    @Transactional
    public MemberLevelResponse updateLevel(AuthenticatedPrincipal principal, long id, MemberLevelUpsertRequest request) {
        LevelInput in = validateLevel(request); long operator=ensureUser(principal);
        if (jdbc.update("UPDATE bf_sport_member_level SET level_code=?,level_name=?,sport_discount_bp=?,min_growth_value=?,benefits=?,status=? WHERE id=?",
                in.code(),in.name(),in.discountBp(),in.minGrowth(),json(in.benefits()),in.status(),id)!=1)
            throw new BusinessException(CommonErrorCode.NOT_FOUND,"会员等级不存在");
        audit(operator,"SPORT_MEMBER_LEVEL_UPDATE",id); return levelById(id);
    }

    @Transactional
    public MemberResponse assignLevel(AuthenticatedPrincipal principal, long memberId, AssignLevelRequest request) {
        if (request == null || !StringUtils.hasText(request.levelCode())) throw invalid("会员等级不能为空");
        String code=request.levelCode().trim().toUpperCase(Locale.ROOT); long operator=ensureUser(principal);
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM bf_sport_member_level WHERE level_code=? AND status='ACTIVE'",Integer.class,code);
        if (exists==null||exists==0) throw invalid("会员等级不存在或已停用");
        if (jdbc.update("UPDATE bf_member_account SET level_code=?,version=version+1 WHERE id=?",code,memberId)!=1)
            throw new BusinessException(CommonErrorCode.NOT_FOUND,"会员不存在");
        audit(operator,"SPORT_MEMBER_LEVEL_ASSIGN",memberId);
        return memberById(memberId);
    }

    private WalletTransactionResponse changeBalance(Account account, long amount, String type, Long orderId,
                                                     String reference, String key, String remark) {
        long after;
        try { after = Math.addExact(account.balance(), amount); }
        catch (ArithmeticException e) { throw invalid("余额金额超出系统范围"); }
        if (after < 0) throw conflict("会员储值余额不足");
        if (jdbc.update("UPDATE bf_member_account SET balance_cent=?,version=version+1 WHERE id=? AND version=?",
                after, account.id(), account.version()) != 1) throw conflict("会员余额已变化，请重试");
        String no=nextNo("WT");
        KeyHolder keys=new GeneratedKeyHolder();
        jdbc.update(c->{PreparedStatement ps=c.prepareStatement("""
                INSERT INTO bf_sport_wallet_transaction
                (member_account_id,transaction_no,transaction_type,amount_cent,balance_before_cent,balance_after_cent,
                 order_id,reference_no,idempotency_key,remark) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1,account.id());ps.setString(2,no);ps.setString(3,type);ps.setLong(4,amount);
            ps.setLong(5,account.balance());ps.setLong(6,after);
            if(orderId==null)ps.setNull(7,java.sql.Types.BIGINT);else ps.setLong(7,orderId);
            ps.setString(8,reference);ps.setString(9,key);ps.setString(10,remark);return ps;},keys);
        return transactionById(keys.getKey().longValue());
    }

    private MemberResponse memberByUser(long userId) {
        List<MemberResponse> rows=jdbc.query(memberSql()+" WHERE m.user_id=?",(rs,n)->member(rs),userId);
        if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"当前用户尚未注册体育会员");return rows.get(0);
    }
    private MemberResponse memberById(long id) {
        List<MemberResponse> rows=jdbc.query(memberSql()+" WHERE m.id=?",(rs,n)->member(rs),id);
        if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"会员不存在");return rows.get(0);
    }
    private String memberSql(){return """
            SELECT m.*,l.level_name,l.sport_discount_bp,l.benefits FROM bf_member_account m
            JOIN bf_sport_member_level l ON l.level_code=m.level_code
            """;}
    private MemberResponse member(java.sql.ResultSet rs)throws java.sql.SQLException{return new MemberResponse(rs.getLong("id"),rs.getString("member_no"),rs.getLong("user_id"),rs.getString("level_code"),rs.getString("level_name"),rs.getInt("sport_discount_bp"),strings(rs.getString("benefits")),rs.getString("status"),rs.getLong("balance_cent"),rs.getInt("points"),rs.getInt("growth_value"),rs.getObject("registered_at",LocalDateTime.class));}
    private Account lockAccount(long userId){return account(userId,true);}
    private Account account(long userId,boolean lock){List<Account> rows=jdbc.query("SELECT id,balance_cent,version FROM bf_member_account WHERE user_id=? AND status='ACTIVE'"+(lock?" FOR UPDATE":""),(rs,n)->new Account(rs.getLong("id"),rs.getLong("balance_cent"),rs.getInt("version")),userId);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"请先注册有效的体育会员");return rows.get(0);}
    private Order lockOrder(long userId,long orderId){List<Order> rows=jdbc.query("SELECT id,order_no,order_type,status,payable_amount_cent,expires_at FROM bf_order WHERE id=? AND user_id=? FOR UPDATE",(rs,n)->new Order(rs.getLong("id"),rs.getString("order_no"),rs.getString("order_type"),rs.getString("status"),rs.getLong("payable_amount_cent"),rs.getObject("expires_at",LocalDateTime.class)),orderId,userId);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"订单不存在或无权访问");return rows.get(0);}
    private PaymentResponse successfulPayment(long orderId){List<PaymentResponse> rows=jdbc.query("SELECT p.id,p.order_id,o.order_no,p.payment_no,p.channel,p.transaction_id,p.amount_cent,p.status,p.paid_at FROM bf_payment p JOIN bf_order o ON o.id=p.order_id WHERE p.order_id=? AND p.status='SUCCESS' ORDER BY p.id LIMIT 1",(rs,n)->paymentRow(rs),orderId);return rows.isEmpty()?null:rows.get(0);}
    private PaymentResponse payment(long id){return jdbc.queryForObject("SELECT p.id,p.order_id,o.order_no,p.payment_no,p.channel,p.transaction_id,p.amount_cent,p.status,p.paid_at FROM bf_payment p JOIN bf_order o ON o.id=p.order_id WHERE p.id=?",(rs,n)->paymentRow(rs),id);}
    private PaymentResponse paymentRow(java.sql.ResultSet rs)throws java.sql.SQLException{return new PaymentResponse(rs.getLong("id"),rs.getLong("order_id"),rs.getString("order_no"),rs.getString("payment_no"),rs.getString("channel"),rs.getString("transaction_id"),rs.getLong("amount_cent"),"CNY",rs.getString("status"),rs.getObject("paid_at",LocalDateTime.class));}
    private WalletTransactionResponse transactionByKey(long accountId,String key){List<WalletTransactionResponse> rows=jdbc.query("SELECT * FROM bf_sport_wallet_transaction WHERE member_account_id=? AND idempotency_key=?",(rs,n)->transaction(rs),accountId,key);return rows.isEmpty()?null:rows.get(0);}
    private WalletTransactionResponse transactionById(long id){return jdbc.queryForObject("SELECT * FROM bf_sport_wallet_transaction WHERE id=?",(rs,n)->transaction(rs),id);}
    private WalletTransactionResponse transaction(java.sql.ResultSet rs)throws java.sql.SQLException{return new WalletTransactionResponse(rs.getLong("id"),rs.getString("transaction_no"),rs.getString("transaction_type"),rs.getLong("amount_cent"),rs.getLong("balance_before_cent"),rs.getLong("balance_after_cent"),rs.getObject("order_id",Long.class),rs.getString("reference_no"),rs.getString("remark"),rs.getObject("created_at",LocalDateTime.class));}
    private MemberLevelResponse levelById(long id){List<MemberLevelResponse> rows=jdbc.query("SELECT * FROM bf_sport_member_level WHERE id=?",(rs,n)->level(rs),id);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"会员等级不存在");return rows.get(0);}
    private MemberLevelResponse level(java.sql.ResultSet rs)throws java.sql.SQLException{return new MemberLevelResponse(rs.getLong("id"),rs.getString("level_code"),rs.getString("level_name"),rs.getInt("sport_discount_bp"),rs.getInt("min_growth_value"),strings(rs.getString("benefits")),rs.getString("status"),rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class));}
    private LevelInput validateLevel(MemberLevelUpsertRequest r){if(r==null)throw invalid("会员等级参数不能为空");String code=required(r.levelCode(),32,"等级编码").toUpperCase(Locale.ROOT);String name=required(r.levelName(),64,"等级名称");int bp=r.sportDiscountBp()==null?10000:r.sportDiscountBp();int growth=r.minGrowthValue()==null?0:r.minGrowthValue();if(bp<1||bp>10000||growth<0)throw invalid("会员折扣或成长值不合法");List<String> benefits=r.benefits()==null?List.of():r.benefits().stream().map(String::trim).filter(StringUtils::hasText).limit(20).toList();return new LevelInput(code,name,bp,growth,benefits,status(r.status()));}
    private long ensureUser(AuthenticatedPrincipal p){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return requireUser(p);}
    private long requireUser(AuthenticatedPrincipal p){List<Long> ids=jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());if(ids.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"当前用户不存在");return ids.get(0);}
    private void audit(long operator,String action,long id){jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?, 'SPORT_MEMBER',?,JSON_OBJECT())",operator,action,String.valueOf(id));}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(Exception e){throw new BusinessException(CommonErrorCode.INTERNAL_ERROR,"会员权益序列化失败");}}
    private List<String> strings(String value){if(!StringUtils.hasText(value))return List.of();try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private String nextNo(String prefix){return prefix+LocalDateTime.now().format(NO_TIME)+UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase(Locale.ROOT);}
    private void validateKey(String key){if(!StringUtils.hasText(key)||key.length()>64)throw invalid("Idempotency-Key不能为空且最长64字符");}
    private void validatePage(int page,int size){if(page<1||size<1||size>100)throw invalid("page从1开始，pageSize范围为1—100");}
    private String required(String v,int max,String name){if(!StringUtils.hasText(v)||v.trim().length()>max)throw invalid(name+"不能为空且最长"+max+"字符");return v.trim();}
    private String optional(String v,int max){if(!StringUtils.hasText(v))return null;if(v.trim().length()>max)throw invalid("备注最长"+max+"字符");return v.trim();}
    private String status(String v){String s=StringUtils.hasText(v)?v.trim().toUpperCase(Locale.ROOT):"ACTIVE";if(!List.of("ACTIVE","INACTIVE").contains(s))throw invalid("状态仅支持ACTIVE或INACTIVE");return s;}
    private BusinessException invalid(String m){return new BusinessException(CommonErrorCode.INVALID_REQUEST,m);}
    private BusinessException conflict(String m){return new BusinessException(CommonErrorCode.PAYMENT_CONFLICT,m);}
    private record Account(long id,long balance,int version){}
    private record Order(long id,String orderNo,String orderType,String status,long payableAmountCent,LocalDateTime expiresAt){}
    private record DiscountData(long bookingId,long originalCent,long userId,long memberId,String levelCode,int discountBp){}
    private record LevelInput(String code,String name,int discountBp,int minGrowth,List<String> benefits,String status){}
}
