package com.iminiapp.backend.sport.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.api.CreateOrderRequest;
import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.order.application.OrderApplicationService;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.sport.api.SportBookingModels.*;
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
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class SportBookingService {
    private final JdbcTemplate jdbc;
    private final OrderApplicationService orders;
    private final SportMemberService members;

    public SportBookingService(JdbcTemplate jdbc, OrderApplicationService orders, SportMemberService members) {
        this.jdbc = jdbc; this.orders = orders; this.members = members;
    }

    @Transactional(readOnly = true)
    public List<PublicVenueResponse> publicVenues() {
        List<VenueHead> venues = jdbc.query("""
                SELECT id,venue_code,name,address,contact_phone,description,open_time,close_time
                FROM bf_sport_venue WHERE status='ACTIVE' ORDER BY id
                """, (rs, n) -> new VenueHead(rs.getLong("id"), rs.getString("venue_code"), rs.getString("name"),
                rs.getString("address"), rs.getString("contact_phone"), rs.getString("description"),
                rs.getObject("open_time", LocalTime.class), rs.getObject("close_time", LocalTime.class)));
        return venues.stream().map(v -> new PublicVenueResponse(v.id(), v.code(), v.name(), v.address(), v.phone(),
                v.description(), v.open(), v.close(), publicCourts(v.id()))).toList();
    }

    @Transactional(readOnly = true)
    public List<PublicCourtResponse> publicCourts(long venueId) {
        return jdbc.query("""
                SELECT c.id,c.venue_id,c.court_type_id,t.name type_name,c.court_code,c.name,c.location,c.capacity,t.base_price_cent
                FROM bf_sport_court c JOIN bf_sport_venue v ON v.id=c.venue_id AND v.status='ACTIVE'
                JOIN bf_sport_court_type t ON t.id=c.court_type_id AND t.status='ACTIVE'
                WHERE c.venue_id=? AND c.status='ACTIVE' ORDER BY t.id,c.id
                """, (rs, n) -> new PublicCourtResponse(rs.getLong("id"), rs.getLong("venue_id"),
                rs.getLong("court_type_id"), rs.getString("type_name"), rs.getString("court_code"),
                rs.getString("name"), rs.getString("location"), rs.getInt("capacity"),
                rs.getLong("base_price_cent")), venueId);
    }

    @Transactional
    public List<AvailableSlotResponse> availability(long courtId, LocalDate date) {
        validateBookingDate(date); CourtInfo court = activeCourt(courtId, false);
        int day = date.getDayOfWeek().getValue(); long skuId = sportSkuId();
        List<Opening> openings = jdbc.query("""
                SELECT start_time,end_time,slot_minutes FROM bf_sport_opening_rule
                WHERE court_id=? AND day_of_week=? AND status='ACTIVE' ORDER BY start_time
                """, (rs, n) -> new Opening(rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getInt("slot_minutes")), courtId, day);
        List<AvailableSlotResponse> result = new ArrayList<>();
        for (Opening opening : openings) {
            for (LocalTime start = opening.start(); start.isBefore(opening.end()); start = start.plusMinutes(opening.minutes())) {
                LocalTime end = start.plusMinutes(opening.minutes());
                if (end.isAfter(opening.end()) || isClosed(courtId, date, start, end)) continue;
                Price price = price(court, date, day, start, end);
                String slot = SportOrderExtension.slotKey(courtId, start, end);
                jdbc.update("""
                        INSERT IGNORE INTO bf_inventory(sku_id,business_date,time_slot,total_quantity,available_quantity,price_cent)
                        VALUES (?,?,?,1,1,?)
                        """, skuId, date, slot, price.amount());
                jdbc.update("""
                        UPDATE bf_inventory SET price_cent=? WHERE sku_id=? AND business_date=? AND time_slot=?
                          AND available_quantity=total_quantity
                        """, price.amount(), skuId, date, slot);
                Integer available = jdbc.queryForObject("""
                        SELECT available_quantity FROM bf_inventory WHERE sku_id=? AND business_date=? AND time_slot=?
                        """, Integer.class, skuId, date, slot);
                result.add(new AvailableSlotResponse(courtId, date, start, end, price.amount(), price.ruleId(),
                        price.ruleName(), available != null && available > 0));
            }
        }
        return result;
    }

    @Transactional
    public BookingResponse create(AuthenticatedPrincipal principal, String idempotencyKey, CreateBookingRequest request) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 64) throw invalid("Idempotency-Key不能为空且最长64字符");
        validateCreate(request);
        BookingResponse existing = existingBooking(principal, idempotencyKey);
        if (existing != null) return existing;
        activeCourt(request.courtId(), true);
        AvailableSlotResponse slot = availability(request.courtId(), request.bookingDate()).stream()
                .filter(v -> v.startTime().equals(request.startTime()) && v.endTime().equals(request.endTime()))
                .findFirst().orElseThrow(() -> conflict("所选时段不在可预订范围内或已临时关闭"));
        if (!slot.available()) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT, "所选场地时段已被预订");
        Map<String, Object> business = new HashMap<>();
        business.put("courtId", request.courtId()); business.put("startTime", request.startTime().toString());
        business.put("endTime", request.endTime().toString());
        if (slot.priceRuleId() != null) business.put("priceRuleId", slot.priceRuleId());
        if (slot.priceRuleName() != null) business.put("priceRuleName", slot.priceRuleName());
        OrderResponse order = orders.create(principal, idempotencyKey, new CreateOrderRequest("SPORT_VENUE",
                List.of(new CreateOrderRequest.Item(sportSkuId(), 1, request.bookingDate(),
                        SportOrderExtension.slotKey(request.courtId(), request.startTime(), request.endTime()), business)), request.remark()));
        members.applyDiscount(order.id());
        return detail(principal, order.id());
    }

    private BookingResponse existingBooking(AuthenticatedPrincipal principal, String idempotencyKey) {
        List<ExistingOrder> rows = jdbc.query("""
                SELECT o.id,u.id user_id FROM bf_order o JOIN bf_user u ON u.id=o.user_id
                JOIN bf_sport_booking b ON b.order_id=o.id
                WHERE u.wechat_openid=? AND o.client_request_id=?
                """, (rs, n) -> new ExistingOrder(rs.getLong("id"), rs.getLong("user_id")),
                principal.databaseOpenId(), idempotencyKey);
        return rows.isEmpty() ? null : booking(rows.get(0).orderId(), rows.get(0).userId());
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> mine(AuthenticatedPrincipal principal, int page, int pageSize) {
        validatePage(page, pageSize); long userId = requireUser(principal);
        long total = count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_order o ON o.id=b.order_id WHERE o.user_id=?", userId);
        List<Long> ids = jdbc.queryForList("""
                SELECT b.order_id FROM bf_sport_booking b JOIN bf_order o ON o.id=b.order_id
                WHERE o.user_id=? ORDER BY b.id DESC LIMIT ? OFFSET ?
                """, Long.class, userId, pageSize, (page - 1) * pageSize);
        List<BookingResponse> items = ids.stream().map(id -> booking(id, userId)).toList();
        return new PageResponse<>(items, page, pageSize, total, total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize));
    }

    @Transactional(readOnly = true)
    public BookingResponse detail(AuthenticatedPrincipal principal, long orderId) { return booking(orderId, requireUser(principal)); }

    @Transactional
    public BookingResponse cancel(AuthenticatedPrincipal principal, long orderId) {
        orders.cancel(principal, orderId);
        jdbc.update("UPDATE bf_sport_booking SET status='CANCELLED',cancelled_at=COALESCE(cancelled_at,CURRENT_TIMESTAMP) WHERE order_id=?", orderId);
        return detail(principal, orderId);
    }

    @Transactional(readOnly = true)
    public List<ClosureResponse> closures(Long courtId, LocalDate startDate, LocalDate endDate) {
        StringBuilder where = new StringBuilder(" WHERE 1=1"); List<Object> args = new ArrayList<>();
        if (courtId != null) { where.append(" AND x.court_id=?"); args.add(courtId); }
        if (startDate != null) { where.append(" AND x.closure_date>=?"); args.add(startDate); }
        if (endDate != null) { where.append(" AND x.closure_date<=?"); args.add(endDate); }
        return jdbc.query("""
                SELECT x.*,c.name court_name FROM bf_sport_closure x JOIN bf_sport_court c ON c.id=x.court_id
                """ + where + " ORDER BY x.closure_date DESC,x.start_time", (rs, n) -> new ClosureResponse(
                rs.getLong("id"), rs.getLong("court_id"), rs.getString("court_name"),
                rs.getObject("closure_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getString("reason"), rs.getString("status"),
                rs.getObject("created_by", Long.class), rs.getObject("created_at", LocalDateTime.class)), args.toArray());
    }

    @Transactional
    public ClosureResponse createClosure(AuthenticatedPrincipal principal, ClosureCreateRequest request) {
        validateClosure(request); activeCourt(request.courtId(), true);
        if (count("""
                SELECT COUNT(*) FROM bf_sport_closure WHERE court_id=? AND closure_date=? AND status='ACTIVE'
                AND start_time<? AND end_time>?
                """, request.courtId(), request.closureDate(), request.endTime(), request.startTime()) > 0)
            throw conflict("该场地已有重叠的临时闭场记录");
        if (count("""
                SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_order o ON o.id=b.order_id
                WHERE b.court_id=? AND b.booking_date=? AND o.status IN ('PENDING_PAYMENT','PAID')
                  AND b.start_time<? AND b.end_time>?
                """, request.courtId(), request.closureDate(), request.endTime(), request.startTime()) > 0)
            throw conflict("闭场时段已有待支付或已支付预订，不能直接关闭");
        long operator = ensureUser(principal); KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_sport_closure(court_id,closure_date,start_time,end_time,reason,created_by)
                    VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, request.courtId()); statement.setObject(2, request.closureDate());
            statement.setObject(3, request.startTime()); statement.setObject(4, request.endTime());
            statement.setString(5, required(request.reason(), 255, "闭场原因")); statement.setLong(6, operator); return statement;
        }, keys);
        long id = keys.getKey().longValue(); audit(operator, "SPORT_CLOSURE_CREATE", id); return closure(id);
    }

    @Transactional
    public ClosureResponse cancelClosure(AuthenticatedPrincipal principal, long id) {
        ClosureResponse value = closure(id); activeCourt(value.courtId(), true); long operator = ensureUser(principal);
        if (jdbc.update("UPDATE bf_sport_closure SET status='CANCELLED' WHERE id=? AND status='ACTIVE'", id) != 1)
            throw conflict("闭场记录不存在或已取消");
        audit(operator, "SPORT_CLOSURE_CANCEL", id); return closure(id);
    }

    private CourtInfo activeCourt(long id, boolean lock) {
        List<CourtInfo> rows = jdbc.query("""
                SELECT c.id,c.venue_id,c.court_type_id,c.name,v.name venue_name,t.name type_name,t.base_price_cent
                FROM bf_sport_court c JOIN bf_sport_venue v ON v.id=c.venue_id AND v.status='ACTIVE'
                JOIN bf_sport_court_type t ON t.id=c.court_type_id AND t.status='ACTIVE'
                WHERE c.id=? AND c.status='ACTIVE'
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new CourtInfo(rs.getLong("id"), rs.getLong("venue_id"),
                rs.getLong("court_type_id"), rs.getString("name"), rs.getString("venue_name"),
                rs.getString("type_name"), rs.getLong("base_price_cent")), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "场地不存在或已停用"); return rows.get(0);
    }

    private Price price(CourtInfo court, LocalDate date, int day, LocalTime start, LocalTime end) {
        List<Price> prices = jdbc.query("""
                SELECT id,name,price_cent FROM bf_sport_price_rule
                WHERE venue_id=? AND status='ACTIVE'
                  AND (court_type_id IS NULL OR court_type_id=?) AND (court_id IS NULL OR court_id=?)
                  AND (start_date IS NULL OR start_date<=?) AND (end_date IS NULL OR end_date>=?)
                  AND (day_of_week IS NULL OR day_of_week=?)
                  AND (start_time IS NULL OR start_time<=?) AND (end_time IS NULL OR end_time>=?)
                ORDER BY priority DESC,(court_id IS NOT NULL) DESC,(court_type_id IS NOT NULL) DESC,id DESC LIMIT 1
                """, (rs, n) -> new Price(rs.getLong("price_cent"), rs.getLong("id"), rs.getString("name")),
                court.venueId(), court.typeId(), court.id(), date, date, day, start, end);
        return prices.isEmpty() ? new Price(court.basePrice(), null, "基础价") : prices.get(0);
    }

    private boolean isClosed(long courtId, LocalDate date, LocalTime start, LocalTime end) {
        return count("""
                SELECT COUNT(*) FROM bf_sport_closure WHERE court_id=? AND closure_date=? AND status='ACTIVE'
                AND start_time<? AND end_time>?
                """, courtId, date, end, start) > 0;
    }

    private BookingResponse booking(long orderId, long userId) {
        List<BookingResponse> rows = jdbc.query("""
                SELECT b.id booking_id,o.id order_id,o.order_no,o.status order_status,o.expires_at,o.remark,o.created_at,
                v.id venue_id,v.name venue_name,t.id type_id,t.name type_name,c.id court_id,c.name court_name,
                b.booking_date,b.start_time,b.end_time,b.price_cent,b.original_price_cent,b.member_discount_cent,
                b.member_account_id,b.member_level_code,b.price_rule_id,b.price_rule_name,b.status booking_status,
                b.confirmed_at,b.cancelled_at,b.refunded_at,b.checked_in_at,b.completed_at,b.verification_id
                FROM bf_sport_booking b JOIN bf_order o ON o.id=b.order_id JOIN bf_sport_court c ON c.id=b.court_id
                JOIN bf_sport_venue v ON v.id=c.venue_id JOIN bf_sport_court_type t ON t.id=c.court_type_id
                WHERE b.order_id=? AND o.user_id=?
                """, (rs, n) -> new BookingResponse(rs.getLong("booking_id"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getString("order_status"), rs.getObject("expires_at", LocalDateTime.class),
                rs.getLong("venue_id"), rs.getString("venue_name"), rs.getLong("type_id"), rs.getString("type_name"),
                rs.getLong("court_id"), rs.getString("court_name"), rs.getObject("booking_date", LocalDate.class),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
                rs.getLong("price_cent"), rs.getLong("original_price_cent"), rs.getLong("member_discount_cent"),
                rs.getObject("member_account_id", Long.class), rs.getString("member_level_code"),
                rs.getObject("price_rule_id", Long.class), rs.getString("price_rule_name"),
                bookingStatus(rs.getString("order_status"), rs.getString("booking_status")), rs.getString("remark"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("confirmed_at", LocalDateTime.class),
                rs.getObject("cancelled_at", LocalDateTime.class), rs.getObject("refunded_at", LocalDateTime.class),
                rs.getObject("checked_in_at", LocalDateTime.class), rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("verification_id", Long.class),
                statusLogs(rs.getLong("booking_id"))), orderId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "体育预订不存在或无权访问"); return rows.get(0);
    }

    private String bookingStatus(String orderStatus, String stored) {
        if ("CANCELLED".equals(orderStatus)) return "CANCELLED";
        if ("REFUNDED".equals(orderStatus)) return "REFUNDED";
        if ("REFUNDING".equals(orderStatus)) return "REFUNDING";
        if ("PAID".equals(orderStatus) && "HELD".equals(stored)) return "CONFIRMED";
        return stored;
    }
    private List<BookingStatusLogResponse> statusLogs(long bookingId) {
        return jdbc.query("""
                SELECT id,from_status,to_status,operator_id,note,created_at
                FROM bf_sport_booking_status_log WHERE booking_id=? ORDER BY id
                """, (rs, n) -> new BookingStatusLogResponse(rs.getLong("id"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getObject("operator_id", Long.class), rs.getString("note"),
                rs.getObject("created_at", LocalDateTime.class)), bookingId);
    }
    private ClosureResponse closure(long id) { return closures(null, null, null).stream().filter(v -> v.id() == id).findFirst().orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "闭场记录不存在")); }
    private long sportSkuId() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_resource_sku WHERE sku_code='SPORT_BOOKING_SLOT' AND status='ACTIVE'", Long.class);
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "体育预订系统SKU未初始化"); return ids.get(0);
    }
    private void validateCreate(CreateBookingRequest r) {
        if (r == null || r.courtId() == null || r.courtId() < 1 || r.startTime() == null || r.endTime() == null
                || !r.startTime().isBefore(r.endTime())) throw invalid("场地、日期和预订时间不能为空");
        validateBookingDate(r.bookingDate());
        if (StringUtils.hasText(r.remark()) && r.remark().trim().length() > 500) throw invalid("备注最长500字符");
    }
    private void validateBookingDate(LocalDate date) {
        if (date == null) throw invalid("预订日期不能为空"); long days = ChronoUnit.DAYS.between(LocalDate.now(), date);
        if (days < 0 || days > 60) throw invalid("仅支持预订今天起60天内的场地");
    }
    private void validateClosure(ClosureCreateRequest r) {
        if (r == null || r.courtId() == null || r.courtId() < 1 || r.closureDate() == null
                || r.startTime() == null || r.endTime() == null || !r.startTime().isBefore(r.endTime()))
            throw invalid("闭场场地、日期和时间不合法");
        if (r.closureDate().isBefore(LocalDate.now())) throw invalid("不能新增过去日期的闭场记录");
    }
    private void validatePage(int page, int size) { if (page < 1 || size < 1 || size > 100) throw invalid("page从1开始，pageSize范围为1—100"); }
    private long requireUser(AuthenticatedPrincipal p) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, p.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户不存在"); return ids.get(0);
    }
    private long ensureUser(AuthenticatedPrincipal p) {
        jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",
                p.databaseOpenId(), p.displayName()); return requireUser(p);
    }
    private void audit(long operator, String action, long id) { jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,'SPORT_CLOSURE',?,JSON_OBJECT())", operator, action, String.valueOf(id)); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private String required(String value, int max, String name) { if (!StringUtils.hasText(value) || value.trim().length() > max) throw invalid(name + "不能为空且最长" + max + "字符"); return value.trim(); }
    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }
    private BusinessException conflict(String message) { return new BusinessException(CommonErrorCode.CONFLICT, message); }

    private record VenueHead(long id, String code, String name, String address, String phone, String description, LocalTime open, LocalTime close) {}
    private record CourtInfo(long id, long venueId, long typeId, String name, String venueName, String typeName, long basePrice) {}
    private record Opening(LocalTime start, LocalTime end, int minutes) {}
    private record Price(long amount, Long ruleId, String ruleName) {}
    private record ExistingOrder(long orderId, long userId) {}
}
