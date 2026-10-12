package com.iminiapp.backend.meeting.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingAvailabilityModels.AvailabilityResponse;
import com.iminiapp.backend.meeting.api.MeetingAvailabilityModels.PriceSnapshot;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MeetingBookingService {
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> TYPES = Set.of("EXTERNAL", "INTERNAL");
    private final JdbcTemplate jdbc;
    private final MeetingAvailabilityService availabilityService;
    private final MeetingApprovalService approvalService;

    public MeetingBookingService(JdbcTemplate jdbc, MeetingAvailabilityService availabilityService,
                                 MeetingApprovalService approvalService) {
        this.jdbc = jdbc;
        this.availabilityService = availabilityService;
        this.approvalService = approvalService;
    }

    @Transactional
    public BookingResponse create(AuthenticatedPrincipal principal, String idempotencyKey,
                                  CreateBookingRequest request) {
        validateCreate(idempotencyKey, request);
        long userId = ensureUser(principal);
        BookingResponse existing = findByRequest(userId, idempotencyKey);
        if (existing != null) return existing;

        Quote quote = quote(request.roomId(), request.bookingDate(), request.attendees(),
                request.startTime(), request.endTime());
        List<ServicePrice> services = priceServices(request.services());
        long serviceAmount = sumServices(services);
        long total = safeTotal(quote.price().venuePriceCent(), serviceAmount);
        long bookingId = insertBooking(userId, idempotencyKey, request, quote, serviceAmount, total);
        insertServices(bookingId, services);
        log(bookingId, null, "DRAFT", userId, "创建会议预订草稿", "MEETING-CREATE:" + bookingId);
        return findOwned(userId, bookingId);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> list(AuthenticatedPrincipal principal) {
        long userId = requireUser(principal);
        return jdbc.queryForList("SELECT id FROM bf_meeting_reservation WHERE user_id=? ORDER BY id DESC",
                Long.class, userId).stream().map(id -> findOwned(userId, id)).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse detail(AuthenticatedPrincipal principal, long bookingId) {
        return findOwned(requireUser(principal), bookingId);
    }

    @Transactional
    public BookingResponse submit(AuthenticatedPrincipal principal, long bookingId) {
        long userId = requireUser(principal);
        ReservationDay day = ownedDay(userId, bookingId);
        lockRoomDay(day.roomId(), day.date());
        BookingForUpdate booking = lockOwned(userId, bookingId);
        if (Set.of("PENDING_PAYMENT", "PENDING_APPROVAL").contains(booking.status())) return findOwned(userId, bookingId);
        if (!"DRAFT".equals(booking.status())) throw conflict("已取消的会议预订不能提交");
        ensureNoOverlap(booking.id(), booking.roomId(), booking.date(), booking.start(), booking.end());

        Quote quote = quote(booking.roomId(), booking.date(), booking.attendees(), booking.start(), booking.end());
        List<ServicePrice> services = repriceServices(bookingId);
        long serviceAmount = sumServices(services);
        long total = safeTotal(quote.price().venuePriceCent(), serviceAmount);
        replaceServiceSnapshots(bookingId, services);
        updatePriceSnapshot(bookingId, quote, serviceAmount, total);

        String targetStatus = "INTERNAL".equals(booking.type()) ? "PENDING_APPROVAL" : "PENDING_PAYMENT";
        long orderId = insertOrder(userId, booking.bookingNo(), booking.type(),
                total, targetStatus, booking.contactName(), booking.contactMobile(), booking.remark());
        insertOrderItem(orderId, bookingId, booking, quote, total, services);
        jdbc.update("""
                UPDATE bf_meeting_reservation SET order_id=?,status=?,submitted_at=CURRENT_TIMESTAMP,
                occupied_at=CURRENT_TIMESTAMP,released_at=NULL WHERE id=?
                """, orderId, targetStatus, bookingId);
        if ("INTERNAL".equals(booking.type())) {
            approvalService.launch(bookingId, userId, "内部会议审批：" + booking.subject());
        }
        log(bookingId, "DRAFT", targetStatus, userId, "提交会议预订并创建统一订单",
                "MEETING-SUBMIT:" + bookingId);
        return findOwned(userId, bookingId);
    }

    @Transactional
    public BookingResponse cancel(AuthenticatedPrincipal principal, long bookingId) {
        long userId = requireUser(principal);
        ReservationDay day = ownedDay(userId, bookingId);
        lockRoomDay(day.roomId(), day.date());
        BookingForUpdate booking = lockOwned(userId, bookingId);
        if ("CANCELLED".equals(booking.status())) return findOwned(userId, bookingId);
        if (booking.orderId() != null) {
            String orderStatus = jdbc.queryForObject("SELECT status FROM bf_order WHERE id=? FOR UPDATE",
                    String.class, booking.orderId());
            if (!Set.of("PENDING_PAYMENT", "PENDING_APPROVAL").contains(orderStatus))
                throw conflict("当前统一订单状态不允许取消");
            jdbc.update("UPDATE bf_order SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=?",
                    booking.orderId());
        }
        jdbc.update("""
                UPDATE bf_meeting_reservation SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,
                released_at=CASE WHEN occupied_at IS NULL THEN released_at ELSE CURRENT_TIMESTAMP END WHERE id=?
                """,
                bookingId);
        log(bookingId, booking.status(), "CANCELLED", userId, "用户取消会议预订",
                "MEETING-CANCEL:" + bookingId);
        return findOwned(userId, bookingId);
    }

    private Quote quote(long roomId, java.time.LocalDate date, int attendees,
                        java.time.LocalTime start, java.time.LocalTime end) {
        AvailabilityResponse response = availabilityService.availability(roomId, date, attendees, start, end);
        if (!response.available() || response.requestedQuote() == null || response.requestedQuote().price() == null)
            throw conflict("会议时段不可预订：" + response.unavailableReason());
        return new Quote(response.requestedQuote().price(), response.quotedAt());
    }

    private List<ServicePrice> priceServices(List<ServiceSelection> selections) {
        if (selections == null || selections.isEmpty()) return List.of();
        if (selections.size() > 20) throw invalid("配套服务最多选择20项");
        Set<Long> ids = new HashSet<>();
        List<ServicePrice> result = new ArrayList<>();
        for (ServiceSelection selection : selections) {
            if (selection == null || selection.serviceId() < 1 || selection.quantity() < 1 || selection.quantity() > 100000)
                throw invalid("配套服务和数量不合法");
            if (!ids.add(selection.serviceId())) throw invalid("同一配套服务不能重复选择");
            result.add(activeService(selection.serviceId(), selection.quantity()));
        }
        return List.copyOf(result);
    }

    private List<ServicePrice> repriceServices(long bookingId) {
        List<ServiceSelection> selections = jdbc.query(
                "SELECT service_id,quantity FROM bf_meeting_reservation_service WHERE booking_id=? ORDER BY id",
                (rs, n) -> new ServiceSelection(rs.getLong("service_id"), rs.getInt("quantity")), bookingId);
        return selections.stream().map(s -> activeService(s.serviceId(), s.quantity())).toList();
    }

    private ServicePrice activeService(long serviceId, int quantity) {
        List<ServicePrice> rows = jdbc.query("""
                SELECT id,service_code,name,service_type,pricing_unit,price_cent
                FROM bf_meeting_service WHERE id=? AND status='ACTIVE'
                """, (rs, n) -> {
            long unit = rs.getLong("price_cent");
            return new ServicePrice(rs.getLong("id"), rs.getString("service_code"), rs.getString("name"),
                    rs.getString("service_type"), rs.getString("pricing_unit"), quantity, unit,
                    Math.multiplyExact(unit, (long) quantity));
        }, serviceId);
        if (rows.isEmpty()) throw conflict("配套服务不存在或已停用：" + serviceId);
        return rows.get(0);
    }

    private long insertBooking(long userId, String key, CreateBookingRequest r, Quote quote,
                               long serviceAmount, long total) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        String bookingNo = nextNumber("MB");
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bf_meeting_reservation
                    (booking_no,user_id,client_request_id,room_id,booking_type,meeting_subject,
                     booking_date,start_time,end_time,attendees,contact_name,contact_mobile,remark,status,
                     venue_price_rule_id,venue_rule_code,venue_rule_name,venue_rule_priority,
                     venue_amount_cent,service_amount_cent,total_amount_cent,price_quoted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, bookingNo); ps.setLong(i++, userId); ps.setString(i++, key); ps.setLong(i++, r.roomId());
            ps.setString(i++, normalizeType(r.bookingType())); ps.setString(i++, r.meetingSubject().trim());
            ps.setObject(i++, r.bookingDate()); ps.setObject(i++, r.startTime()); ps.setObject(i++, r.endTime());
            ps.setInt(i++, r.attendees()); ps.setString(i++, r.contactName().trim());
            ps.setString(i++, r.contactMobile().trim()); ps.setString(i++, normalizeOptional(r.remark()));
            bindPrice(ps, i, quote, serviceAmount, total);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private void bindPrice(PreparedStatement ps, int i, Quote quote, long serviceAmount, long total)
            throws java.sql.SQLException {
        ps.setLong(i++, quote.price().priceRuleId()); ps.setString(i++, quote.price().ruleCode());
        ps.setString(i++, quote.price().ruleName()); ps.setInt(i++, quote.price().priority());
        ps.setLong(i++, quote.price().venuePriceCent()); ps.setLong(i++, serviceAmount);
        ps.setLong(i++, total); ps.setObject(i, quote.quotedAt());
    }

    private void insertServices(long bookingId, List<ServicePrice> services) {
        for (ServicePrice s : services) jdbc.update("""
                INSERT INTO bf_meeting_reservation_service
                (booking_id,service_id,service_code,service_name,service_type,pricing_unit,quantity,unit_price_cent,amount_cent)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, bookingId, s.id(), s.code(), s.name(), s.type(), s.unit(), s.quantity(), s.unitPrice(), s.amount());
    }

    private void replaceServiceSnapshots(long bookingId, List<ServicePrice> services) {
        jdbc.update("DELETE FROM bf_meeting_reservation_service WHERE booking_id=?", bookingId);
        insertServices(bookingId, services);
    }

    private void updatePriceSnapshot(long bookingId, Quote quote, long serviceAmount, long total) {
        jdbc.update("""
                UPDATE bf_meeting_reservation SET venue_price_rule_id=?,venue_rule_code=?,venue_rule_name=?,
                venue_rule_priority=?,venue_amount_cent=?,service_amount_cent=?,total_amount_cent=?,price_quoted_at=?
                WHERE id=?
                """, quote.price().priceRuleId(), quote.price().ruleCode(), quote.price().ruleName(),
                quote.price().priority(), quote.price().venuePriceCent(), serviceAmount, total, quote.quotedAt(), bookingId);
    }

    private long insertOrder(long userId, String bookingNo, String type, long total,
                             String status, String contactName, String mobile, String remark) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        String orderNo = nextNumber("MO");
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bf_order(order_no,user_id,client_request_id,order_type,status,
                    total_amount_cent,discount_amount_cent,payable_amount_cent,paid_amount_cent,expires_at,
                    contact_name,contact_mobile,remark)
                    VALUES (?,?,?,'MEETING_BOOKING',?,?,0,?,0,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, orderNo); ps.setLong(2, userId);
            ps.setString(3, "MEETING:" + bookingNo); ps.setString(4, status);
            ps.setLong(5, total); ps.setLong(6, total);
            ps.setObject(7, "EXTERNAL".equals(type) ? LocalDateTime.now().plusMinutes(30) : null);
            ps.setString(8, contactName); ps.setString(9, mobile);
            ps.setString(10, joinRemark(bookingNo, remark));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private void insertOrderItem(long orderId, long bookingId, BookingForUpdate b, Quote quote,
                                 long total, List<ServicePrice> services) {
        Long skuId = jdbc.queryForObject("SELECT id FROM bf_resource_sku WHERE sku_code='MEETING-BOOKING-GENERIC'",
                Long.class);
        jdbc.update("""
                INSERT INTO bf_order_item(order_id,sku_id,sku_code,sku_name,resource_name,resource_type,
                quantity,unit_price_cent,amount_cent,service_date,time_slot,snapshot)
                VALUES (?,?,'MEETING-BOOKING-GENERIC','会议预订服务',?,'MEETING_BOOKING',1,?,?,?, ?,
                JSON_OBJECT('bookingId',?,'bookingNo',?,'roomId',?,'meetingSubject',?,'attendees',?,
                            'priceRuleId',?,'priceRuleCode',?,'venueAmountCent',?,'serviceAmountCent',?,
                            'serviceCount',?))
                """, orderId, skuId, b.roomName(), total, total, b.date(), b.start() + "-" + b.end(),
                bookingId, b.bookingNo(), b.roomId(), b.subject(), b.attendees(), quote.price().priceRuleId(),
                quote.price().ruleCode(), quote.price().venuePriceCent(), sumServices(services), services.size());
    }

    private BookingResponse findByRequest(long userId, String requestId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_meeting_reservation WHERE user_id=? AND client_request_id=?",
                Long.class, userId, requestId);
        return ids.isEmpty() ? null : findOwned(userId, ids.get(0));
    }

    private BookingResponse findOwned(long userId, long bookingId) {
        List<BookingResponse> rows = jdbc.query("""
                SELECT b.*,r.room_code,r.name room_name,r.location,o.order_no
                FROM bf_meeting_reservation b JOIN bf_meeting_room r ON r.id=b.room_id
                LEFT JOIN bf_order o ON o.id=b.order_id WHERE b.id=? AND b.user_id=?
                """, (rs, n) -> new BookingResponse(rs.getLong("id"), rs.getString("booking_no"),
                nullableLong(rs, "order_id"), rs.getString("order_no"), rs.getString("booking_type"),
                rs.getString("meeting_subject"), rs.getString("status"), rs.getLong("room_id"),
                rs.getString("room_code"), rs.getString("room_name"), rs.getString("location"),
                rs.getObject("booking_date", java.time.LocalDate.class), rs.getObject("start_time", java.time.LocalTime.class),
                rs.getObject("end_time", java.time.LocalTime.class), rs.getInt("attendees"),
                rs.getString("contact_name"), rs.getString("contact_mobile"), rs.getString("remark"),
                new PriceSnapshotResponse(rs.getLong("venue_price_rule_id"), rs.getString("venue_rule_code"),
                        rs.getString("venue_rule_name"), rs.getInt("venue_rule_priority"),
                        rs.getLong("venue_amount_cent"), rs.getLong("service_amount_cent"),
                        rs.getLong("total_amount_cent"), rs.getObject("price_quoted_at", LocalDateTime.class)),
                services(bookingId), rs.getObject("submitted_at", LocalDateTime.class),
                rs.getObject("occupied_at", LocalDateTime.class), rs.getObject("released_at", LocalDateTime.class),
                rs.getObject("cancelled_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class)),
                bookingId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议预订不存在或无权访问");
        return rows.get(0);
    }

    private List<BookingServiceResponse> services(long bookingId) {
        return jdbc.query("""
                SELECT service_id,service_code,service_name,service_type,pricing_unit,quantity,unit_price_cent,amount_cent
                FROM bf_meeting_reservation_service WHERE booking_id=? ORDER BY id
                """, (rs, n) -> new BookingServiceResponse(rs.getLong("service_id"), rs.getString("service_code"),
                rs.getString("service_name"), rs.getString("service_type"), rs.getString("pricing_unit"),
                rs.getInt("quantity"), rs.getLong("unit_price_cent"), rs.getLong("amount_cent")), bookingId);
    }

    private BookingForUpdate lockOwned(long userId, long id) {
        List<BookingForUpdate> rows = jdbc.query("""
                SELECT b.id,b.booking_no,b.client_request_id,b.room_id,b.order_id,b.booking_type,b.meeting_subject,
                       b.booking_date,b.start_time,b.end_time,b.attendees,b.contact_name,b.contact_mobile,b.remark,b.status,
                       r.name room_name FROM bf_meeting_reservation b JOIN bf_meeting_room r ON r.id=b.room_id
                WHERE b.id=? AND b.user_id=? FOR UPDATE
                """, (rs, n) -> new BookingForUpdate(rs.getLong("id"), rs.getString("booking_no"),
                rs.getString("client_request_id"), rs.getLong("room_id"), nullableLong(rs, "order_id"),
                rs.getString("booking_type"), rs.getString("meeting_subject"),
                rs.getObject("booking_date", java.time.LocalDate.class), rs.getObject("start_time", java.time.LocalTime.class),
                rs.getObject("end_time", java.time.LocalTime.class), rs.getInt("attendees"),
                rs.getString("contact_name"), rs.getString("contact_mobile"), rs.getString("remark"),
                rs.getString("status"), rs.getString("room_name")), id, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议预订不存在或无权访问");
        return rows.get(0);
    }

    private ReservationDay ownedDay(long userId, long bookingId) {
        List<ReservationDay> rows = jdbc.query("""
                SELECT room_id,booking_date FROM bf_meeting_reservation WHERE id=? AND user_id=?
                """, (rs, n) -> new ReservationDay(rs.getLong("room_id"),
                rs.getObject("booking_date", java.time.LocalDate.class)), bookingId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议预订不存在或无权访问");
        return rows.get(0);
    }

    private void lockRoomDay(long roomId, java.time.LocalDate date) {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_meeting_room_day_lock WHERE room_id=? AND booking_date=?
                """, Integer.class, roomId, date);
        if (exists == null || exists == 0) {
            // 首次创建日期锁时先串行锁定会议室，避免并发 INSERT IGNORE 在唯一键上死锁。
            jdbc.queryForObject("SELECT id FROM bf_meeting_room WHERE id=? FOR UPDATE", Long.class, roomId);
            jdbc.update("""
                    INSERT IGNORE INTO bf_meeting_room_day_lock(room_id,booking_date) VALUES (?,?)
                    """, roomId, date);
        }
        jdbc.queryForObject("""
                SELECT room_id FROM bf_meeting_room_day_lock WHERE room_id=? AND booking_date=? FOR UPDATE
                """, Long.class, roomId, date);
    }

    private void ensureNoOverlap(long bookingId, long roomId, java.time.LocalDate date,
                                 java.time.LocalTime start, java.time.LocalTime end) {
        List<String> conflicts = jdbc.queryForList("""
                SELECT booking_no FROM bf_meeting_reservation
                WHERE room_id=? AND booking_date=? AND id<>?
                  AND status IN ('PENDING_PAYMENT','PENDING_APPROVAL','PAID','APPROVED',
                                 'CONFIRMED','VERIFIED','COMPLETED')
                  AND start_time<? AND end_time>?
                ORDER BY start_time,id FOR UPDATE
                """, String.class, roomId, date, bookingId, end, start);
        if (!conflicts.isEmpty()) throw conflict("所选会议室时段已被占用，请重新选择");
    }

    private long ensureUser(AuthenticatedPrincipal p) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, p.databaseOpenId());
        if (!ids.isEmpty()) return ids.get(0);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO bf_user(wechat_openid,nickname,real_name,status,last_login_at)
                    VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, p.databaseOpenId()); ps.setString(2, p.displayName()); ps.setString(3, p.displayName());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private long requireUser(AuthenticatedPrincipal p) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, p.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户尚无会议预订记录");
        return ids.get(0);
    }

    private void validateCreate(String key, CreateBookingRequest r) {
        if (!StringUtils.hasText(key) || key.length() > 64) throw invalid("Idempotency-Key不能为空且最长64字符");
        if (r == null || r.roomId() < 1 || r.bookingDate() == null || r.startTime() == null || r.endTime() == null
                || r.attendees() < 1 || !StringUtils.hasText(r.meetingSubject()) || r.meetingSubject().trim().length() > 200
                || !StringUtils.hasText(r.contactName()) || r.contactName().trim().length() > 64
                || !StringUtils.hasText(r.contactMobile()) || r.contactMobile().trim().length() > 32)
            throw invalid("会议预订申请信息不完整或格式不合法");
        normalizeType(r.bookingType());
        if (normalizeOptional(r.remark()) != null && normalizeOptional(r.remark()).length() > 500)
            throw invalid("备注最长500字符");
    }

    private String normalizeType(String type) {
        String value = type == null ? "EXTERNAL" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(value)) throw invalid("bookingType仅支持EXTERNAL或INTERNAL");
        return value;
    }

    private long sumServices(List<ServicePrice> services) {
        long total = 0;
        for (ServicePrice s : services) total = Math.addExact(total, s.amount());
        return total;
    }

    private long safeTotal(long venue, long services) {
        long total;
        try { total = Math.addExact(venue, services); }
        catch (ArithmeticException ex) { throw invalid("会议预订金额超出系统范围"); }
        if (total > Integer.MAX_VALUE) throw invalid("会议预订金额超出统一订单范围");
        return total;
    }

    private void log(long id, String from, String to, long actor, String note, String key) {
        jdbc.update("""
                INSERT INTO bf_meeting_reservation_status_log(booking_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,?,?,?,?,?)
                """, id, from, to, actor, note, key);
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private String normalizeOptional(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String joinRemark(String bookingNo, String remark) {
        String prefix = "会议预订号：" + bookingNo;
        String value = StringUtils.hasText(remark) ? prefix + "；" + remark : prefix;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
    private String nextNumber(String prefix) {
        return prefix + LocalDateTime.now().format(NUMBER_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }
    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }
    private BusinessException conflict(String message) { return new BusinessException(CommonErrorCode.ORDER_CONFLICT, message); }

    private record Quote(PriceSnapshot price, LocalDateTime quotedAt) {}
    private record ServicePrice(long id, String code, String name, String type, String unit,
                                int quantity, long unitPrice, long amount) {}
    private record BookingForUpdate(long id, String bookingNo, String clientRequestId, long roomId, Long orderId,
                                    String type, String subject, java.time.LocalDate date, java.time.LocalTime start,
                                    java.time.LocalTime end, int attendees, String contactName, String contactMobile,
                                    String remark, String status, String roomName) {}
    private record ReservationDay(long roomId, java.time.LocalDate date) {}
}
