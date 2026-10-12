package com.iminiapp.backend.meeting.application;

import com.iminiapp.backend.meeting.api.MeetingAvailabilityModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** 根据会议室开放规则生成可订日历，并确定性匹配收费规则。 */
@Service
public class MeetingAvailabilityService {
    public static final String AVAILABLE = "AVAILABLE";
    public static final String ROOM_INACTIVE = "ROOM_INACTIVE";
    public static final String CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";
    public static final String DATE_IN_PAST = "DATE_IN_PAST";
    public static final String DATE_NOT_OPEN = "DATE_NOT_OPEN";
    public static final String OUTSIDE_BUSINESS_HOURS = "OUTSIDE_BUSINESS_HOURS";
    public static final String OUTSIDE_OPENING_RULE = "OUTSIDE_OPENING_RULE";
    public static final String SLOT_ALIGNMENT_REQUIRED = "SLOT_ALIGNMENT_REQUIRED";
    public static final String TIME_SLOT_OCCUPIED = "TIME_SLOT_OCCUPIED";
    public static final String PRICE_RULE_NOT_FOUND = "PRICE_RULE_NOT_FOUND";

    private final JdbcTemplate jdbc;

    public MeetingAvailabilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse availability(long roomId, LocalDate date, int attendees,
                                             LocalTime requestedStart, LocalTime requestedEnd) {
        validateRequest(roomId, date, attendees, requestedStart, requestedEnd);
        Room room = room(roomId);
        int dayOfWeek = date.getDayOfWeek().getValue();
        LocalDateTime quotedAt = LocalDateTime.now();
        List<Opening> openings = openings(roomId, dayOfWeek);
        List<Occupancy> occupancies = occupancies(roomId, date);

        Unavailable global = globalUnavailable(room, date, attendees, openings);
        List<AvailableSlotResponse> slots = generateSlots(room, date, attendees, openings, occupancies, global);
        RequestedQuoteResponse requested = requestedStart == null ? null
                : quoteRequested(room, date, attendees, requestedStart, requestedEnd, openings, occupancies, global);

        boolean available;
        Unavailable summary;
        if (requested != null) {
            available = requested.available();
            summary = available ? null : new Unavailable(requested.unavailableCode(), requested.unavailableReason());
        } else {
            available = slots.stream().anyMatch(AvailableSlotResponse::available);
            summary = available ? null : firstReason(global, slots);
        }
        return new AvailabilityResponse(room.id(), room.code(), room.name(), room.location(), room.capacity(),
                room.open(), room.close(), date, dayOfWeek, attendees, available,
                summary == null ? null : summary.code(), summary == null ? null : summary.message(),
                requested, slots, quotedAt);
    }

    private void validateRequest(long roomId, LocalDate date, int attendees,
                                 LocalTime requestedStart, LocalTime requestedEnd) {
        if (roomId < 1 || date == null || attendees < 1 || attendees > 100000)
            throw invalid("会议室、预订日期或参会人数不合法");
        if ((requestedStart == null) != (requestedEnd == null))
            throw invalid("期望开始时间和结束时间必须同时提供");
        if (requestedStart != null && !requestedStart.isBefore(requestedEnd))
            throw invalid("期望开始时间必须早于结束时间");
    }

    private Room room(long id) {
        List<Room> rows = jdbc.query("""
                SELECT id,room_code,name,location,capacity,open_time,close_time,status
                FROM bf_meeting_room WHERE id=?
                """, (rs, n) -> new Room(rs.getLong("id"), rs.getString("room_code"), rs.getString("name"),
                rs.getString("location"), rs.getInt("capacity"), rs.getObject("open_time", LocalTime.class),
                rs.getObject("close_time", LocalTime.class), rs.getString("status")), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议室不存在");
        return rows.get(0);
    }

    private List<Opening> openings(long roomId, int dayOfWeek) {
        return jdbc.query("""
                SELECT id,start_time,end_time,slot_minutes
                FROM bf_meeting_opening_rule
                WHERE room_id=? AND day_of_week=? AND status='ACTIVE'
                ORDER BY start_time,id
                """, (rs, n) -> new Opening(rs.getLong("id"), rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getInt("slot_minutes")), roomId, dayOfWeek);
    }

    private Unavailable globalUnavailable(Room room, LocalDate date, int attendees, List<Opening> openings) {
        if (!"ACTIVE".equals(room.status())) return unavailable(ROOM_INACTIVE, "会议室已停用");
        if (attendees > room.capacity()) return unavailable(CAPACITY_EXCEEDED, "参会人数超过会议室容量");
        if (date.isBefore(LocalDate.now())) return unavailable(DATE_IN_PAST, "预订日期不能早于今天");
        if (openings.isEmpty()) return unavailable(DATE_NOT_OPEN, "该日期没有配置可预订时段");
        return null;
    }

    private List<AvailableSlotResponse> generateSlots(Room room, LocalDate date, int attendees,
                                                      List<Opening> openings, List<Occupancy> occupancies,
                                                      Unavailable global) {
        List<AvailableSlotResponse> result = new ArrayList<>();
        for (Opening opening : openings) {
            for (LocalTime start = opening.start(); start.isBefore(opening.end()); start = start.plusMinutes(opening.slotMinutes())) {
                LocalTime end = start.plusMinutes(opening.slotMinutes());
                if (end.isAfter(opening.end())) break;
                Unavailable reason = global;
                PriceSnapshot price = null;
                if (reason == null && occupied(occupancies, start, end))
                    reason = unavailable(TIME_SLOT_OCCUPIED, "该时段已被其他会议占用");
                if (reason == null) {
                    price = price(room.id(), date, attendees, start, end);
                    if (price == null) reason = unavailable(PRICE_RULE_NOT_FOUND, "该时段没有匹配的收费规则");
                }
                result.add(new AvailableSlotResponse(opening.id(), start, end, opening.slotMinutes(),
                        reason == null, reason == null ? null : reason.code(), reason == null ? null : reason.message(), price));
            }
        }
        return List.copyOf(result);
    }

    private RequestedQuoteResponse quoteRequested(Room room, LocalDate date, int attendees,
                                                  LocalTime start, LocalTime end, List<Opening> openings,
                                                  List<Occupancy> occupancies, Unavailable global) {
        long duration = Duration.between(start, end).toMinutes();
        Unavailable reason = global;
        PriceSnapshot price = null;
        if (reason == null && (start.isBefore(room.open()) || end.isAfter(room.close())))
            reason = unavailable(OUTSIDE_BUSINESS_HOURS, "期望时段不在会议室营业时间内");
        Opening matched = reason == null ? containingOpening(openings, start, end) : null;
        if (reason == null && matched == null)
            reason = unavailable(OUTSIDE_OPENING_RULE, "期望时段不在该日期的开放规则内");
        if (reason == null && !aligned(matched, start, duration))
            reason = unavailable(SLOT_ALIGNMENT_REQUIRED, "期望时段必须按开放规则的时段长度对齐");
        if (reason == null && occupied(occupancies, start, end))
            reason = unavailable(TIME_SLOT_OCCUPIED, "期望时段已被其他会议占用");
        if (reason == null) {
            price = price(room.id(), date, attendees, start, end);
            if (price == null) reason = unavailable(PRICE_RULE_NOT_FOUND, "期望时段没有匹配的收费规则，暂不可提交预订");
        }
        return new RequestedQuoteResponse(start, end, duration, reason == null,
                reason == null ? null : reason.code(), reason == null ? null : reason.message(),
                price == null ? 0 : price.venuePriceCent(), price);
    }

    private Opening containingOpening(List<Opening> openings, LocalTime start, LocalTime end) {
        return openings.stream().filter(o -> !start.isBefore(o.start()) && !end.isAfter(o.end()))
                .findFirst().orElse(null);
    }

    private boolean aligned(Opening opening, LocalTime start, long durationMinutes) {
        return Duration.between(opening.start(), start).toMinutes() % opening.slotMinutes() == 0
                && durationMinutes % opening.slotMinutes() == 0;
    }

    private List<Occupancy> occupancies(long roomId, LocalDate date) {
        return jdbc.query("""
                SELECT start_time,end_time FROM bf_meeting_reservation
                WHERE room_id=? AND booking_date=?
                  AND status IN ('PENDING_PAYMENT','PENDING_APPROVAL','PAID','APPROVED',
                                 'CONFIRMED','VERIFIED','COMPLETED')
                ORDER BY start_time,id
                """, (rs, n) -> new Occupancy(rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class)), roomId, date);
    }

    private boolean occupied(List<Occupancy> occupancies, LocalTime start, LocalTime end) {
        return occupancies.stream().anyMatch(o -> o.start().isBefore(end) && o.end().isAfter(start));
    }

    private PriceSnapshot price(long roomId, LocalDate date, int attendees, LocalTime start, LocalTime end) {
        List<PriceSnapshot> rows = jdbc.query("""
                SELECT id,rule_code,name,priority,price_cent
                FROM bf_meeting_price_rule
                WHERE room_id=? AND status='ACTIVE'
                  AND (start_date IS NULL OR start_date<=?) AND (end_date IS NULL OR end_date>=?)
                  AND (day_of_week IS NULL OR day_of_week=?)
                  AND (start_time IS NULL OR start_time<=?) AND (end_time IS NULL OR end_time>=?)
                  AND (min_attendees IS NULL OR min_attendees<=?) AND (max_attendees IS NULL OR max_attendees>=?)
                ORDER BY priority DESC,
                  (start_date IS NOT NULL) DESC,(day_of_week IS NOT NULL) DESC,
                  (start_time IS NOT NULL) DESC,(min_attendees IS NOT NULL) DESC,id DESC
                LIMIT 1
                """, (rs, n) -> new PriceSnapshot(rs.getLong("id"), rs.getString("rule_code"),
                rs.getString("name"), rs.getInt("priority"), rs.getLong("price_cent")),
                roomId, date, date, date.getDayOfWeek().getValue(), start, end, attendees, attendees);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Unavailable firstReason(Unavailable global, List<AvailableSlotResponse> slots) {
        if (global != null) return global;
        if (slots.isEmpty()) return unavailable(DATE_NOT_OPEN, "该日期没有配置可预订时段");
        AvailableSlotResponse first = slots.get(0);
        return unavailable(first.unavailableCode(), first.unavailableReason());
    }

    private Unavailable unavailable(String code, String message) { return new Unavailable(code, message); }
    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }

    private record Room(long id, String code, String name, String location, int capacity,
                        LocalTime open, LocalTime close, String status) {}
    private record Opening(long id, LocalTime start, LocalTime end, int slotMinutes) {}
    private record Occupancy(LocalTime start, LocalTime end) {}
    private record Unavailable(String code, String message) {}
}
