package com.iminiapp.backend.meeting.application;

import com.iminiapp.backend.meeting.api.MeetingOperationModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class MeetingOperationService {
    private static final Set<String> OCCUPIED = Set.of("PENDING_PAYMENT", "PENDING_APPROVAL", "PAID", "APPROVED", "CONFIRMED", "VERIFIED", "COMPLETED");
    private final JdbcTemplate jdbc;

    public MeetingOperationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<BookingLedgerResponse> bookings(LocalDate startDate, LocalDate endDate, Long roomId,
                                                String status, String bookingType, String keyword) {
        Range range = range(startDate, endDate);
        StringBuilder sql = new StringBuilder("""
                SELECT b.id,b.booking_no,o.order_no,b.booking_type,b.meeting_subject,b.status,b.room_id,
                       r.room_code,r.name room_name,b.booking_date,b.start_time,b.end_time,b.attendees,
                       b.contact_name,b.contact_mobile,b.venue_amount_cent,b.service_amount_cent,
                       b.total_amount_cent,COALESCE(o.paid_amount_cent,0) paid_amount_cent,
                       ai.status approval_status,ai.current_step approval_current_step,
                       mb.status usage_status,mb.usage_started_at,mb.usage_ended_at,b.created_at
                FROM bf_meeting_reservation b
                JOIN bf_meeting_room r ON r.id=b.room_id
                LEFT JOIN bf_order o ON o.id=b.order_id
                LEFT JOIN bf_approval_instance ai ON ai.business_type='MEETING_BOOKING' AND ai.business_id=b.id
                LEFT JOIN bf_meeting_booking mb ON mb.reservation_id=b.id
                WHERE b.booking_date BETWEEN ? AND ?
                """);
        List<Object> args = new ArrayList<>(List.of(range.start(), range.end()));
        if (roomId != null) { sql.append(" AND b.room_id=?"); args.add(roomId); }
        if (StringUtils.hasText(status)) { sql.append(" AND b.status=?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        if (StringUtils.hasText(bookingType)) { sql.append(" AND b.booking_type=?"); args.add(bookingType.trim().toUpperCase(Locale.ROOT)); }
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (b.booking_no LIKE ? OR b.meeting_subject LIKE ? OR b.contact_name LIKE ? OR b.contact_mobile LIKE ?)");
            String like = "%" + keyword.trim() + "%"; args.add(like); args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY b.booking_date DESC,b.start_time DESC,b.id DESC");
        return jdbc.query(sql.toString(), (rs, n) -> new BookingLedgerResponse(
                rs.getLong("id"), rs.getString("booking_no"), rs.getString("order_no"),
                rs.getString("booking_type"), rs.getString("meeting_subject"), rs.getString("status"),
                rs.getLong("room_id"), rs.getString("room_code"), rs.getString("room_name"),
                rs.getObject("booking_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getInt("attendees"),
                rs.getString("contact_name"), rs.getString("contact_mobile"),
                rs.getLong("venue_amount_cent"), rs.getLong("service_amount_cent"),
                rs.getLong("total_amount_cent"), rs.getLong("paid_amount_cent"),
                rs.getString("approval_status"), nullableInt(rs.getObject("approval_current_step")),
                rs.getString("usage_status"), rs.getObject("usage_started_at", LocalDateTime.class),
                rs.getObject("usage_ended_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class)
        ), args.toArray());
    }

    @Transactional(readOnly = true)
    public OverviewResponse overview(LocalDate startDate, LocalDate endDate) {
        Range r = range(startDate, endDate);
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) booking_count,
                  SUM(status IN ('PENDING_PAYMENT','PENDING_APPROVAL')) pending_count,
                  SUM(status IN ('PAID','APPROVED','CONFIRMED')) approved_count,
                  SUM(status='VERIFIED') verified_count,SUM(status='COMPLETED') completed_count,
                  SUM(status='CANCELLED') cancelled_count,SUM(status='REJECTED') rejected_count,
                  COALESCE(SUM(total_amount_cent),0) quoted_amount,
                  COALESCE(SUM(service_amount_cent),0) service_amount,
                  COALESCE(SUM(attendees),0) attendee_count
                FROM bf_meeting_reservation WHERE booking_date BETWEEN ? AND ?
                """, r.start(), r.end());
        Long paid = jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.paid_amount_cent),0) FROM bf_meeting_reservation b
                JOIN bf_order o ON o.id=b.order_id WHERE b.booking_date BETWEEN ? AND ?
                """, Long.class, r.start(), r.end());
        Long verified = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_meeting_verification_log l JOIN bf_meeting_reservation b ON b.id=l.reservation_id
                WHERE l.action='CHECK_IN' AND b.booking_date BETWEEN ? AND ?
                """, Long.class, r.start(), r.end());
        long capacity = roomUtilization(r.start(), r.end()).stream().mapToLong(RoomUtilizationResponse::capacityMinutes).sum();
        long occupied = occupiedMinutes(null, r.start(), r.end());
        return new OverviewResponse(r.start(), r.end(), number(totals,"booking_count"), number(totals,"pending_count"),
                number(totals,"approved_count"), number(totals,"verified_count"), number(totals,"completed_count"),
                number(totals,"cancelled_count"), number(totals,"rejected_count"), number(totals,"quoted_amount"),
                paid == null ? 0 : paid, number(totals,"service_amount"), number(totals,"attendee_count"),
                verified == null ? 0 : verified, capacity, occupied, rate(occupied, capacity));
    }

    @Transactional(readOnly = true)
    public List<RoomUtilizationResponse> roomUtilization(LocalDate startDate, LocalDate endDate) {
        Range range = range(startDate, endDate);
        return jdbc.query("SELECT id,room_code,name FROM bf_meeting_room ORDER BY id", (rs, n) -> {
            long roomId = rs.getLong("id");
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM bf_meeting_reservation WHERE room_id=? AND booking_date BETWEEN ? AND ? AND status NOT IN ('DRAFT','CANCELLED','REJECTED')", Long.class, roomId, range.start(), range.end());
            long capacity = capacityMinutes(roomId, range.start(), range.end());
            long occupied = occupiedMinutes(roomId, range.start(), range.end());
            return new RoomUtilizationResponse(roomId, rs.getString("room_code"), rs.getString("name"),
                    count == null ? 0 : count, capacity, occupied, rate(occupied, capacity));
        });
    }

    @Transactional(readOnly = true)
    public List<ServiceSalesResponse> serviceSales(LocalDate startDate, LocalDate endDate) {
        Range r = range(startDate, endDate);
        return jdbc.query("""
                SELECT s.service_id,s.service_code,s.service_name,s.service_type,COUNT(DISTINCT s.booking_id) booking_count,
                       SUM(s.quantity) quantity,SUM(s.amount_cent) sales_amount
                FROM bf_meeting_reservation_service s JOIN bf_meeting_reservation b ON b.id=s.booking_id
                WHERE b.booking_date BETWEEN ? AND ? AND b.status NOT IN ('DRAFT','CANCELLED','REJECTED')
                GROUP BY s.service_id,s.service_code,s.service_name,s.service_type ORDER BY sales_amount DESC
                """, (rs,n) -> new ServiceSalesResponse(rs.getLong("service_id"),rs.getString("service_code"),
                rs.getString("service_name"),rs.getString("service_type"),rs.getLong("booking_count"),
                rs.getLong("quantity"),rs.getLong("sales_amount")), r.start(), r.end());
    }

    public byte[] exportCsv(LocalDate startDate, LocalDate endDate, Long roomId, String status,
                            String bookingType, String keyword) {
        StringBuilder csv = new StringBuilder("\uFEFF预订号,订单号,类型,会议主题,状态,会议室,日期,开始时间,结束时间,人数,联系人,手机号,场地金额(分),服务金额(分),总金额(分),实付金额(分),审批状态,使用状态\r\n");
        for (BookingLedgerResponse b : bookings(startDate,endDate,roomId,status,bookingType,keyword)) {
            csv.append(cell(b.bookingNo())).append(',').append(cell(b.orderNo())).append(',').append(cell(b.bookingType())).append(',')
                    .append(cell(b.meetingSubject())).append(',').append(cell(b.status())).append(',').append(cell(b.roomName())).append(',')
                    .append(b.bookingDate()).append(',').append(b.startTime()).append(',').append(b.endTime()).append(',').append(b.attendees()).append(',')
                    .append(cell(b.contactName())).append(',').append(cell(b.contactMobile())).append(',').append(b.venueAmountCent()).append(',')
                    .append(b.serviceAmountCent()).append(',').append(b.totalAmountCent()).append(',').append(b.paidAmountCent()).append(',')
                    .append(cell(b.approvalStatus())).append(',').append(cell(b.usageStatus())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private long capacityMinutes(long roomId, LocalDate start, LocalDate end) {
        List<Opening> rules = jdbc.query("SELECT day_of_week,start_time,end_time FROM bf_meeting_opening_rule WHERE room_id=? AND status='ACTIVE'",
                (rs,n)->new Opening(rs.getInt(1),rs.getTime(2).toLocalTime(),rs.getTime(3).toLocalTime()),roomId);
        long total=0; for(LocalDate day=start;!day.isAfter(end);day=day.plusDays(1)) for(Opening o:rules)
            if(o.day()==day.getDayOfWeek().getValue()) total += ChronoUnit.MINUTES.between(o.start(),o.end());
        return total;
    }

    private long occupiedMinutes(Long roomId, LocalDate start, LocalDate end) {
        String sql="SELECT start_time,end_time,status FROM bf_meeting_reservation WHERE booking_date BETWEEN ? AND ?"+(roomId==null?"":" AND room_id=?");
        Object[] args=roomId==null?new Object[]{start,end}:new Object[]{start,end,roomId};
        return jdbc.query(sql, rs->{long total=0;while(rs.next()) if(OCCUPIED.contains(rs.getString("status")))
            total+=ChronoUnit.MINUTES.between(rs.getTime("start_time").toLocalTime(),rs.getTime("end_time").toLocalTime());return total;},args);
    }

    private Range range(LocalDate start, LocalDate end) {
        LocalDate e=end==null?LocalDate.now().plusDays(30):end; LocalDate s=start==null?e.minusDays(30):start;
        if(s.isAfter(e)||ChronoUnit.DAYS.between(s,e)>366)
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"统计日期范围必须在 366 天内");
        return new Range(s,e);
    }
    private long number(Map<String,Object> map,String key){Object value=map.get(key);return value instanceof Number n?n.longValue():0;}
    private double rate(long used,long total){return total==0?0:Math.round(used*10000.0/total)/100.0;}
    private Integer nullableInt(Object value){return value instanceof Number n?n.intValue():null;}
    private String cell(Object value){String s=value==null?"":String.valueOf(value);return "\""+s.replace("\"","\"\"")+"\"";}
    private record Range(LocalDate start,LocalDate end){}
    private record Opening(int day,LocalTime start,LocalTime end){}
}
