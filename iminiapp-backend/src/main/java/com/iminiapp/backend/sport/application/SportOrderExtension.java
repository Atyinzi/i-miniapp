package com.iminiapp.backend.sport.application;

import com.iminiapp.backend.order.extension.OrderBusinessContext;
import com.iminiapp.backend.order.extension.OrderBusinessExtension;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Component
public class SportOrderExtension implements OrderBusinessExtension {
    private final JdbcTemplate jdbc;

    public SportOrderExtension(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public String resourceType() { return "SPORT_VENUE"; }

    @Override
    public void validate(OrderBusinessContext context) {
        if (context.quantity() != 1 || context.serviceDate() == null) throw invalid("体育场地每个订单只能预订一个时段");
        long days = ChronoUnit.DAYS.between(LocalDate.now(), context.serviceDate());
        if (days < 0 || days > 60) throw invalid("体育场地仅支持预订今天起60天内的时段");
        BookingData data = data(context.businessData());
        if (!slotKey(data.courtId(), data.start(), data.end()).equals(context.timeSlot())) throw invalid("体育预订时段参数不一致");
        List<Opening> openings = jdbc.query("""
                SELECT r.start_time,r.end_time,r.slot_minutes FROM bf_sport_court c
                JOIN bf_sport_venue v ON v.id=c.venue_id AND v.status='ACTIVE'
                JOIN bf_sport_court_type t ON t.id=c.court_type_id AND t.status='ACTIVE'
                JOIN bf_sport_opening_rule r ON r.court_id=c.id AND r.status='ACTIVE'
                WHERE c.id=? AND c.status='ACTIVE' AND r.day_of_week=?
                """, (rs, n) -> new Opening(rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getInt("slot_minutes")),
                data.courtId(), context.serviceDate().getDayOfWeek().getValue());
        boolean valid = openings.stream().anyMatch(opening -> !data.start().isBefore(opening.start())
                && !data.end().isAfter(opening.end())
                && Duration.between(data.start(), data.end()).toMinutes() == opening.minutes()
                && Duration.between(opening.start(), data.start()).toMinutes() % opening.minutes() == 0);
        if (!valid) throw new BusinessException(CommonErrorCode.CONFLICT, "所选场地时段不在可预订范围内");
        Integer closed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_sport_closure WHERE court_id=? AND closure_date=? AND status='ACTIVE'
                AND start_time<? AND end_time>?
                """, Integer.class, data.courtId(), context.serviceDate(), data.end(), data.start());
        if (closed != null && closed > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "所选场地时段已临时关闭");
    }

    @Override
    public void save(long orderId, long orderItemId, OrderBusinessContext context) {
        BookingData data = data(context.businessData());
        jdbc.update("""
                INSERT INTO bf_sport_booking(order_id,order_item_id,court_id,booking_date,start_time,end_time,
                price_rule_id,price_rule_name,price_cent,original_price_cent,status)
                SELECT ?,?,?,?,?,?,?,?,?,?, 'HELD' FROM bf_order_item WHERE id=?
                """, orderId, orderItemId, data.courtId(), context.serviceDate(), data.start(), data.end(),
                data.priceRuleId(), data.priceRuleName(),
                jdbc.queryForObject("SELECT unit_price_cent FROM bf_order_item WHERE id=?", Long.class, orderItemId),
                jdbc.queryForObject("SELECT unit_price_cent FROM bf_order_item WHERE id=?", Long.class, orderItemId), orderItemId);
        jdbc.update("""
                INSERT INTO bf_sport_booking_status_log(booking_id,from_status,to_status,note,idempotency_key)
                SELECT id,NULL,'HELD','待支付订单已锁定场地时段',CONCAT('SPORT-HELD:',id)
                FROM bf_sport_booking WHERE order_id=?
                """, orderId);
    }

    private BookingData data(Map<String, Object> values) {
        if (values == null) throw invalid("体育预订业务参数不能为空");
        try {
            long courtId = Long.parseLong(String.valueOf(values.get("courtId")));
            LocalTime start = LocalTime.parse(String.valueOf(values.get("startTime")));
            LocalTime end = LocalTime.parse(String.valueOf(values.get("endTime")));
            if (courtId < 1 || !start.isBefore(end)) throw invalid("体育预订业务参数不合法");
            Long ruleId = values.get("priceRuleId") == null ? null : Long.parseLong(String.valueOf(values.get("priceRuleId")));
            String ruleName = values.get("priceRuleName") == null ? null : String.valueOf(values.get("priceRuleName"));
            return new BookingData(courtId, start, end, ruleId, ruleName);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalid("体育预订业务参数格式不正确"); }
    }

    static String slotKey(long courtId, LocalTime start, LocalTime end) {
        return "SPORT:" + courtId + ":" + start.toString() + "-" + end.toString();
    }
    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }
    private record BookingData(long courtId, LocalTime start, LocalTime end, Long priceRuleId, String priceRuleName) {}
    private record Opening(LocalTime start, LocalTime end, int minutes) {}
}
