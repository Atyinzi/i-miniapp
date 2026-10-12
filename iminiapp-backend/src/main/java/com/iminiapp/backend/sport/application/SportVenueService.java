package com.iminiapp.backend.sport.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.sport.api.SportVenueModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SportVenueService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> PRICE_TYPES = Set.of("BASE", "SEASON", "HOLIDAY");
    private final JdbcTemplate jdbc;

    public SportVenueService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<VenueResponse> venues(String status) {
        String filter = StringUtils.hasText(status) ? " WHERE v.status=?" : "";
        Object[] args = StringUtils.hasText(status) ? new Object[]{normalizeStatus(status)} : new Object[]{};
        return jdbc.query("""
                SELECT v.*,
                  (SELECT COUNT(*) FROM bf_sport_court_type t WHERE t.venue_id=v.id) type_count,
                  (SELECT COUNT(*) FROM bf_sport_court c WHERE c.venue_id=v.id) court_count
                FROM bf_sport_venue v
                """ + filter + " ORDER BY v.id", (rs, n) -> new VenueResponse(
                rs.getLong("id"), rs.getString("venue_code"), rs.getString("name"), rs.getString("address"),
                rs.getString("contact_phone"), rs.getString("description"), rs.getObject("open_time", LocalTime.class),
                rs.getObject("close_time", LocalTime.class), rs.getString("status"), rs.getLong("type_count"),
                rs.getLong("court_count"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)), args);
    }

    @Transactional
    public VenueResponse createVenue(AuthenticatedPrincipal principal, VenueUpsertRequest request) {
        VenueValues value = validateVenue(request);
        requireUnique("SELECT COUNT(*) FROM bf_sport_venue WHERE venue_code=?", "场馆编码已存在", value.code());
        jdbc.update("""
                INSERT INTO bf_sport_venue(venue_code,name,address,contact_phone,description,open_time,close_time,status)
                VALUES (?,?,?,?,?,?,?,?)
                """, value.code(), value.name(), value.address(), value.phone(), value.description(),
                value.openTime(), value.closeTime(), value.status());
        long id = lastId(); audit(principal, "SPORT_VENUE_CREATE", "SPORT_VENUE", id, value.code());
        return venue(id);
    }

    @Transactional
    public VenueResponse updateVenue(AuthenticatedPrincipal principal, long id, VenueUpsertRequest request) {
        venue(id); VenueValues value = validateVenue(request);
        requireUnique("SELECT COUNT(*) FROM bf_sport_venue WHERE venue_code=? AND id<>?", "场馆编码已存在", value.code(), id);
        if ("INACTIVE".equals(value.status()) && count("SELECT COUNT(*) FROM bf_sport_court WHERE venue_id=? AND status='ACTIVE'", id) > 0)
            throw conflict("停用场馆前请先停用所属场地");
        jdbc.update("""
                UPDATE bf_sport_venue SET venue_code=?,name=?,address=?,contact_phone=?,description=?,
                open_time=?,close_time=?,status=? WHERE id=?
                """, value.code(), value.name(), value.address(), value.phone(), value.description(),
                value.openTime(), value.closeTime(), value.status(), id);
        audit(principal, "SPORT_VENUE_UPDATE", "SPORT_VENUE", id, value.code()); return venue(id);
    }

    @Transactional(readOnly = true)
    public List<CourtTypeResponse> courtTypes(Long venueId, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (venueId != null) { where.append(" AND t.venue_id=?"); args.add(venueId); }
        if (StringUtils.hasText(status)) { where.append(" AND t.status=?"); args.add(normalizeStatus(status)); }
        return jdbc.query("""
                SELECT t.*,v.name venue_name,(SELECT COUNT(*) FROM bf_sport_court c WHERE c.court_type_id=t.id) court_count
                FROM bf_sport_court_type t JOIN bf_sport_venue v ON v.id=t.venue_id
                """ + where + " ORDER BY t.venue_id,t.id", (rs, n) -> new CourtTypeResponse(
                rs.getLong("id"), rs.getLong("venue_id"), rs.getString("venue_name"), rs.getString("type_code"),
                rs.getString("name"), rs.getString("description"), rs.getLong("base_price_cent"),
                rs.getString("status"), rs.getLong("court_count"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)), args.toArray());
    }

    @Transactional
    public CourtTypeResponse createCourtType(AuthenticatedPrincipal principal, CourtTypeUpsertRequest request) {
        CourtTypeValues value = validateCourtType(request); venue(value.venueId());
        requireUnique("SELECT COUNT(*) FROM bf_sport_court_type WHERE venue_id=? AND type_code=?",
                "同一场馆下场地类型编码已存在", value.venueId(), value.code());
        jdbc.update("""
                INSERT INTO bf_sport_court_type(venue_id,type_code,name,description,base_price_cent,status)
                VALUES (?,?,?,?,?,?)
                """, value.venueId(), value.code(), value.name(), value.description(), value.price(), value.status());
        long id = lastId(); audit(principal, "SPORT_COURT_TYPE_CREATE", "SPORT_COURT_TYPE", id, value.code());
        return courtType(id);
    }

    @Transactional
    public CourtTypeResponse updateCourtType(AuthenticatedPrincipal principal, long id, CourtTypeUpsertRequest request) {
        CourtTypeResponse current = courtType(id); CourtTypeValues value = validateCourtType(request); venue(value.venueId());
        requireUnique("SELECT COUNT(*) FROM bf_sport_court_type WHERE venue_id=? AND type_code=? AND id<>?",
                "同一场馆下场地类型编码已存在", value.venueId(), value.code(), id);
        if (current.venueId() != value.venueId() && current.courtCount() > 0) throw conflict("已有场地的类型不能更换所属场馆");
        jdbc.update("UPDATE bf_sport_court_type SET venue_id=?,type_code=?,name=?,description=?,base_price_cent=?,status=? WHERE id=?",
                value.venueId(), value.code(), value.name(), value.description(), value.price(), value.status(), id);
        audit(principal, "SPORT_COURT_TYPE_UPDATE", "SPORT_COURT_TYPE", id, value.code()); return courtType(id);
    }

    @Transactional(readOnly = true)
    public List<CourtResponse> courts(Long venueId, Long courtTypeId, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (venueId != null) { where.append(" AND c.venue_id=?"); args.add(venueId); }
        if (courtTypeId != null) { where.append(" AND c.court_type_id=?"); args.add(courtTypeId); }
        if (StringUtils.hasText(status)) { where.append(" AND c.status=?"); args.add(normalizeStatus(status)); }
        return jdbc.query("""
                SELECT c.*,v.name venue_name,t.name type_name,
                  (SELECT COUNT(*) FROM bf_sport_opening_rule r WHERE r.court_id=c.id) opening_count
                FROM bf_sport_court c JOIN bf_sport_venue v ON v.id=c.venue_id
                JOIN bf_sport_court_type t ON t.id=c.court_type_id
                """ + where + " ORDER BY c.venue_id,c.court_type_id,c.id", (rs, n) -> new CourtResponse(
                rs.getLong("id"), rs.getLong("venue_id"), rs.getString("venue_name"), rs.getLong("court_type_id"),
                rs.getString("type_name"), rs.getString("court_code"), rs.getString("name"),
                rs.getString("location"), rs.getInt("capacity"), rs.getString("status"),
                rs.getLong("opening_count"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)), args.toArray());
    }

    @Transactional
    public CourtResponse createCourt(AuthenticatedPrincipal principal, CourtUpsertRequest request) {
        CourtValues value = validateCourt(request); validateCourtScope(value.venueId(), value.typeId());
        requireUnique("SELECT COUNT(*) FROM bf_sport_court WHERE venue_id=? AND court_code=?",
                "同一场馆下场地编码已存在", value.venueId(), value.code());
        jdbc.update("""
                INSERT INTO bf_sport_court(venue_id,court_type_id,court_code,name,location,capacity,status)
                VALUES (?,?,?,?,?,?,?)
                """, value.venueId(), value.typeId(), value.code(), value.name(), value.location(), value.capacity(), value.status());
        long id = lastId(); audit(principal, "SPORT_COURT_CREATE", "SPORT_COURT", id, value.code()); return court(id);
    }

    @Transactional
    public CourtResponse updateCourt(AuthenticatedPrincipal principal, long id, CourtUpsertRequest request) {
        court(id); CourtValues value = validateCourt(request); validateCourtScope(value.venueId(), value.typeId());
        requireUnique("SELECT COUNT(*) FROM bf_sport_court WHERE venue_id=? AND court_code=? AND id<>?",
                "同一场馆下场地编码已存在", value.venueId(), value.code(), id);
        jdbc.update("UPDATE bf_sport_court SET venue_id=?,court_type_id=?,court_code=?,name=?,location=?,capacity=?,status=? WHERE id=?",
                value.venueId(), value.typeId(), value.code(), value.name(), value.location(), value.capacity(), value.status(), id);
        audit(principal, "SPORT_COURT_UPDATE", "SPORT_COURT", id, value.code()); return court(id);
    }

    @Transactional(readOnly = true)
    public List<OpeningRuleResponse> openingRules(Long courtId) {
        String filter = courtId == null ? "" : " WHERE r.court_id=?";
        Object[] args = courtId == null ? new Object[]{} : new Object[]{courtId};
        return jdbc.query("""
                SELECT r.*,c.name court_name FROM bf_sport_opening_rule r
                JOIN bf_sport_court c ON c.id=r.court_id
                """ + filter + " ORDER BY r.court_id,r.day_of_week,r.start_time", (rs, n) -> new OpeningRuleResponse(
                rs.getLong("id"), rs.getLong("court_id"), rs.getString("court_name"), rs.getInt("day_of_week"),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
                rs.getInt("slot_minutes"), rs.getString("status"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)), args);
    }

    @Transactional
    public OpeningRuleResponse createOpeningRule(AuthenticatedPrincipal principal, OpeningRuleUpsertRequest request) {
        OpeningValues value = validateOpening(request, null); ensureOpeningNoOverlap(value, null);
        jdbc.update("INSERT INTO bf_sport_opening_rule(court_id,day_of_week,start_time,end_time,slot_minutes,status) VALUES (?,?,?,?,?,?)",
                value.courtId(), value.day(), value.start(), value.end(), value.minutes(), value.status());
        long id = lastId(); audit(principal, "SPORT_OPENING_RULE_CREATE", "SPORT_OPENING_RULE", id, String.valueOf(value.courtId()));
        return openingRule(id);
    }

    @Transactional
    public OpeningRuleResponse updateOpeningRule(AuthenticatedPrincipal principal, long id, OpeningRuleUpsertRequest request) {
        openingRule(id); OpeningValues value = validateOpening(request, id); ensureOpeningNoOverlap(value, id);
        jdbc.update("UPDATE bf_sport_opening_rule SET court_id=?,day_of_week=?,start_time=?,end_time=?,slot_minutes=?,status=? WHERE id=?",
                value.courtId(), value.day(), value.start(), value.end(), value.minutes(), value.status(), id);
        audit(principal, "SPORT_OPENING_RULE_UPDATE", "SPORT_OPENING_RULE", id, String.valueOf(value.courtId())); return openingRule(id);
    }

    @Transactional(readOnly = true)
    public List<PriceRuleResponse> priceRules(Long venueId, Long courtTypeId, Long courtId, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (venueId != null) { where.append(" AND p.venue_id=?"); args.add(venueId); }
        if (courtTypeId != null) { where.append(" AND p.court_type_id=?"); args.add(courtTypeId); }
        if (courtId != null) { where.append(" AND p.court_id=?"); args.add(courtId); }
        if (StringUtils.hasText(status)) { where.append(" AND p.status=?"); args.add(normalizeStatus(status)); }
        return jdbc.query("""
                SELECT p.*,v.name venue_name,t.name type_name,c.name court_name
                FROM bf_sport_price_rule p JOIN bf_sport_venue v ON v.id=p.venue_id
                LEFT JOIN bf_sport_court_type t ON t.id=p.court_type_id
                LEFT JOIN bf_sport_court c ON c.id=p.court_id
                """ + where + " ORDER BY p.priority DESC,p.id", (rs, n) -> mapPriceRule(rs), args.toArray());
    }

    @Transactional
    public PriceRuleResponse createPriceRule(AuthenticatedPrincipal principal, PriceRuleUpsertRequest request) {
        PriceValues value = validatePrice(request); validatePriceScope(value);
        requireUnique("SELECT COUNT(*) FROM bf_sport_price_rule WHERE rule_code=?", "价格规则编码已存在", value.code());
        jdbc.update("""
                INSERT INTO bf_sport_price_rule(venue_id,court_type_id,court_id,rule_code,name,rule_type,start_date,
                end_date,day_of_week,start_time,end_time,price_cent,priority,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.venueId(), value.typeId(), value.courtId(), value.code(), value.name(), value.type(),
                value.startDate(), value.endDate(), value.day(), value.startTime(), value.endTime(), value.price(),
                value.priority(), value.status());
        long id = lastId(); audit(principal, "SPORT_PRICE_RULE_CREATE", "SPORT_PRICE_RULE", id, value.code()); return priceRule(id);
    }

    @Transactional
    public PriceRuleResponse updatePriceRule(AuthenticatedPrincipal principal, long id, PriceRuleUpsertRequest request) {
        priceRule(id); PriceValues value = validatePrice(request); validatePriceScope(value);
        requireUnique("SELECT COUNT(*) FROM bf_sport_price_rule WHERE rule_code=? AND id<>?", "价格规则编码已存在", value.code(), id);
        jdbc.update("""
                UPDATE bf_sport_price_rule SET venue_id=?,court_type_id=?,court_id=?,rule_code=?,name=?,rule_type=?,
                start_date=?,end_date=?,day_of_week=?,start_time=?,end_time=?,price_cent=?,priority=?,status=? WHERE id=?
                """, value.venueId(), value.typeId(), value.courtId(), value.code(), value.name(), value.type(),
                value.startDate(), value.endDate(), value.day(), value.startTime(), value.endTime(), value.price(),
                value.priority(), value.status(), id);
        audit(principal, "SPORT_PRICE_RULE_UPDATE", "SPORT_PRICE_RULE", id, value.code()); return priceRule(id);
    }

    private VenueResponse venue(long id) { return venues(null).stream().filter(v -> v.id() == id).findFirst().orElseThrow(() -> notFound("场馆不存在")); }
    private CourtTypeResponse courtType(long id) { return courtTypes(null, null).stream().filter(v -> v.id() == id).findFirst().orElseThrow(() -> notFound("场地类型不存在")); }
    private CourtResponse court(long id) { return courts(null, null, null).stream().filter(v -> v.id() == id).findFirst().orElseThrow(() -> notFound("场地不存在")); }
    private OpeningRuleResponse openingRule(long id) { return openingRules(null).stream().filter(v -> v.id() == id).findFirst().orElseThrow(() -> notFound("开放时段规则不存在")); }
    private PriceRuleResponse priceRule(long id) { return priceRules(null, null, null, null).stream().filter(v -> v.id() == id).findFirst().orElseThrow(() -> notFound("价格规则不存在")); }

    private PriceRuleResponse mapPriceRule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PriceRuleResponse(rs.getLong("id"), rs.getLong("venue_id"), rs.getString("venue_name"),
                rs.getObject("court_type_id", Long.class), rs.getString("type_name"), rs.getObject("court_id", Long.class),
                rs.getString("court_name"), rs.getString("rule_code"), rs.getString("name"), rs.getString("rule_type"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getObject("day_of_week", Integer.class), rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getLong("price_cent"), rs.getInt("priority"),
                rs.getString("status"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private VenueValues validateVenue(VenueUpsertRequest r) {
        if (r == null || r.openTime() == null || r.closeTime() == null || !r.openTime().isBefore(r.closeTime()))
            throw invalid("场馆营业开始时间必须早于结束时间");
        return new VenueValues(required(r.venueCode(), 50, "场馆编码").toUpperCase(Locale.ROOT),
                required(r.name(), 120, "场馆名称"), required(r.address(), 255, "场馆地址"),
                optional(r.contactPhone(), 32), optional(r.description(), 2000), r.openTime(), r.closeTime(), normalizeStatus(r.status()));
    }
    private CourtTypeValues validateCourtType(CourtTypeUpsertRequest r) {
        if (r == null || r.venueId() == null || r.venueId() < 1 || r.basePriceCent() == null || r.basePriceCent() < 0)
            throw invalid("场馆和基础价格不合法");
        return new CourtTypeValues(r.venueId(), required(r.typeCode(), 50, "类型编码").toUpperCase(Locale.ROOT),
                required(r.name(), 100, "类型名称"), optional(r.description(), 500), r.basePriceCent(), normalizeStatus(r.status()));
    }
    private CourtValues validateCourt(CourtUpsertRequest r) {
        if (r == null || r.venueId() == null || r.venueId() < 1 || r.courtTypeId() == null || r.courtTypeId() < 1
                || r.capacity() == null || r.capacity() < 1 || r.capacity() > 10000) throw invalid("场馆、场地类型或容量不合法");
        return new CourtValues(r.venueId(), r.courtTypeId(), required(r.courtCode(), 50, "场地编码").toUpperCase(Locale.ROOT),
                required(r.name(), 100, "场地名称"), optional(r.location(), 255), r.capacity(), normalizeStatus(r.status()));
    }
    private OpeningValues validateOpening(OpeningRuleUpsertRequest r, Long ignoredId) {
        if (r == null || r.courtId() == null || r.courtId() < 1 || r.dayOfWeek() == null || r.dayOfWeek() < 1 || r.dayOfWeek() > 7
                || r.startTime() == null || r.endTime() == null || !r.startTime().isBefore(r.endTime())
                || r.slotMinutes() == null || r.slotMinutes() < 15 || r.slotMinutes() > 240) throw invalid("开放日、时间或时段长度不合法");
        CourtHours hours = jdbc.query("""
                SELECT v.open_time,v.close_time FROM bf_sport_court c JOIN bf_sport_venue v ON v.id=c.venue_id WHERE c.id=?
                """, rs -> rs.next() ? new CourtHours(rs.getObject(1, LocalTime.class), rs.getObject(2, LocalTime.class)) : null, r.courtId());
        if (hours == null) throw notFound("场地不存在");
        if (r.startTime().isBefore(hours.open()) || r.endTime().isAfter(hours.close())) throw invalid("开放时段必须位于场馆营业时间内");
        long duration = Duration.between(r.startTime(), r.endTime()).toMinutes();
        if (duration % r.slotMinutes() != 0) throw invalid("开放时段必须能被单个预订时长整除");
        return new OpeningValues(r.courtId(), r.dayOfWeek(), r.startTime(), r.endTime(), r.slotMinutes(), normalizeStatus(r.status()));
    }
    private PriceValues validatePrice(PriceRuleUpsertRequest r) {
        if (r == null || r.venueId() == null || r.venueId() < 1 || r.priceCent() == null || r.priceCent() < 0)
            throw invalid("场馆和价格不合法");
        String type = required(r.ruleType(), 20, "价格类型").toUpperCase(Locale.ROOT);
        if (!PRICE_TYPES.contains(type)) throw invalid("价格类型仅支持BASE、SEASON、HOLIDAY");
        if (!"BASE".equals(type) && (r.startDate() == null || r.endDate() == null)) throw invalid("淡旺季或节假日价格必须设置起止日期");
        if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate())) throw invalid("价格结束日期不能早于开始日期");
        if ((r.startTime() == null) != (r.endTime() == null) || (r.startTime() != null && !r.startTime().isBefore(r.endTime())))
            throw invalid("价格规则时间范围不合法");
        if (r.dayOfWeek() != null && (r.dayOfWeek() < 1 || r.dayOfWeek() > 7)) throw invalid("星期范围为1—7");
        return new PriceValues(r.venueId(), r.courtTypeId(), r.courtId(),
                required(r.ruleCode(), 64, "价格规则编码").toUpperCase(Locale.ROOT), required(r.name(), 120, "价格规则名称"),
                type, r.startDate(), r.endDate(), r.dayOfWeek(), r.startTime(), r.endTime(), r.priceCent(),
                r.priority() == null ? 0 : r.priority(), normalizeStatus(r.status()));
    }

    private void validateCourtScope(long venueId, long typeId) {
        venue(venueId); CourtTypeResponse type = courtType(typeId);
        if (type.venueId() != venueId) throw invalid("场地类型不属于所选场馆");
    }
    private void validatePriceScope(PriceValues value) {
        venue(value.venueId());
        if (value.typeId() != null) validateCourtScope(value.venueId(), value.typeId());
        if (value.courtId() != null) {
            CourtResponse court = court(value.courtId());
            if (court.venueId() != value.venueId()) throw invalid("价格规则场地不属于所选场馆");
            if (value.typeId() != null && court.courtTypeId() != value.typeId()) throw invalid("价格规则场地不属于所选类型");
        }
    }
    private void ensureOpeningNoOverlap(OpeningValues value, Long ignoredId) {
        if (!"ACTIVE".equals(value.status())) return;
        long matches = count("""
                SELECT COUNT(*) FROM bf_sport_opening_rule WHERE court_id=? AND day_of_week=? AND status='ACTIVE'
                AND (? IS NULL OR id<>?) AND start_time<? AND end_time>?
                """, value.courtId(), value.day(), ignoredId, ignoredId, value.end(), value.start());
        if (matches > 0) throw conflict("同一场地同一天的开放时段不能重叠");
    }

    private String normalizeStatus(String value) {
        String status = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "ACTIVE";
        if (!STATUSES.contains(status)) throw invalid("状态仅支持ACTIVE或INACTIVE"); return status;
    }
    private String required(String value, int max, String name) {
        if (!StringUtils.hasText(value)) throw invalid(name + "不能为空"); String text = value.trim();
        if (text.length() > max) throw invalid(name + "最长" + max + "字符"); return text;
    }
    private String optional(String value, int max) { return StringUtils.hasText(value) ? required(value, max, "字段") : null; }
    private long lastId() { return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private void requireUnique(String sql, String message, Object... args) { if (count(sql, args) > 0) throw conflict(message); }
    private void audit(AuthenticatedPrincipal principal, String action, String target, long id, String value) {
        jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",
                principal.databaseOpenId(), principal.displayName());
        Long operator = jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, principal.databaseOpenId());
        jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,?,?,JSON_OBJECT('value',?))",
                operator, action, target, String.valueOf(id), value);
    }
    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }
    private BusinessException conflict(String message) { return new BusinessException(CommonErrorCode.CONFLICT, message); }
    private BusinessException notFound(String message) { return new BusinessException(CommonErrorCode.NOT_FOUND, message); }

    private record VenueValues(String code, String name, String address, String phone, String description,
                               LocalTime openTime, LocalTime closeTime, String status) {}
    private record CourtTypeValues(long venueId, String code, String name, String description, long price, String status) {}
    private record CourtValues(long venueId, long typeId, String code, String name, String location, int capacity, String status) {}
    private record OpeningValues(long courtId, int day, LocalTime start, LocalTime end, int minutes, String status) {}
    private record PriceValues(long venueId, Long typeId, Long courtId, String code, String name, String type,
                               LocalDate startDate, LocalDate endDate, Integer day, LocalTime startTime,
                               LocalTime endTime, long price, int priority, String status) {}
    private record CourtHours(LocalTime open, LocalTime close) {}
}
