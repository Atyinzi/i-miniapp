package com.iminiapp.backend.meeting.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingFulfillmentModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MeetingFulfillmentService {
    private final JdbcTemplate jdbc;
    public MeetingFulfillmentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UsageRecordResponse revoke(AuthenticatedPrincipal principal, long verificationId, RevokeRequest request) {
        requireStaff(principal);
        long operatorId = requireUser(principal);
        String reason = request == null || !StringUtils.hasText(request.reason()) ? null : request.reason().trim();
        if (reason == null || reason.length() > 255) throw invalid("撤销核销原因不能为空且最长255字符");
        List<LockedUsage> rows = jdbc.query("""
                SELECT r.id reservation_id,r.booking_type,r.status reservation_status,r.order_id,
                       v.id verification_id,v.status verification_status,u.order_item_id,u.status usage_status
                FROM bf_meeting_reservation r JOIN bf_meeting_booking u ON u.reservation_id=r.id
                JOIN bf_verification v ON v.id=u.verification_id
                WHERE v.id=? FOR UPDATE
                """, (rs,n)->new LockedUsage(rs.getLong("reservation_id"),rs.getString("booking_type"),
                rs.getString("reservation_status"),rs.getLong("order_id"),rs.getLong("verification_id"),
                rs.getString("verification_status"),rs.getLong("order_item_id"),rs.getString("usage_status")),
                verificationId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议核销记录不存在");
        LockedUsage usage = rows.get(0);
        if (!"USED".equals(usage.verificationStatus()) || !"CHECKED_IN".equals(usage.usageStatus())
                || !"VERIFIED".equals(usage.reservationStatus()))
            throw conflict("只有尚未结束使用的已核销会议可以撤销核销");
        String restored = "INTERNAL".equals(usage.type()) ? "APPROVED" : "PAID";
        jdbc.update("""
                UPDATE bf_verification SET status='UNUSED',verified_by=NULL,verified_at=NULL,
                verify_channel=NULL,device_no=NULL WHERE id=? AND status='USED'
                """, verificationId);
        jdbc.update("UPDATE bf_meeting_reservation SET status=? WHERE id=?", restored, usage.reservationId());
        jdbc.update("UPDATE bf_meeting_booking SET status='REVOKED',usage_ended_at=CURRENT_TIMESTAMP WHERE reservation_id=?",
                usage.reservationId());
        jdbc.update("""
                INSERT INTO bf_meeting_verification_log
                (reservation_id,verification_id,action,operator_id,status_before,status_after,note)
                VALUES (?,?,'REVOKE',?,'VERIFIED',?,?)
                """, usage.reservationId(), verificationId, operatorId, restored, reason);
        jdbc.update("""
                INSERT INTO bf_meeting_reservation_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'VERIFIED',?,?,?,?)
                """, usage.reservationId(), restored, operatorId, reason,
                "MEETING-VERIFY-REVOKE:" + verificationId);
        audit(operatorId, "MEETING_VERIFICATION_REVOKE", verificationId, reason);
        return usage(usage.reservationId());
    }

    @Transactional
    public UsageRecordResponse complete(AuthenticatedPrincipal principal, long bookingId) {
        requireStaff(principal);
        long operatorId = requireUser(principal);
        List<CompleteState> rows = jdbc.query("""
                SELECT r.id,r.order_id,r.status,o.status order_status,u.verification_id,u.status usage_status
                FROM bf_meeting_reservation r JOIN bf_order o ON o.id=r.order_id
                JOIN bf_meeting_booking u ON u.reservation_id=r.id
                WHERE r.id=? FOR UPDATE
                """, (rs,n)->new CompleteState(rs.getLong("id"),rs.getLong("order_id"),
                rs.getString("status"),rs.getString("order_status"),rs.getLong("verification_id"),rs.getString("usage_status")), bookingId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议使用记录不存在");
        CompleteState state = rows.get(0);
        if ("COMPLETED".equals(state.status()) && "COMPLETED".equals(state.usageStatus())) return usage(bookingId);
        if (!"VERIFIED".equals(state.status()) || !"CHECKED_IN".equals(state.usageStatus()))
            throw conflict("只有已核销且使用中的会议可以结束使用");
        jdbc.update("UPDATE bf_meeting_reservation SET status='COMPLETED' WHERE id=?", bookingId);
        jdbc.update("UPDATE bf_order SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=?", state.orderId());
        jdbc.update("UPDATE bf_meeting_booking SET status='COMPLETED',usage_ended_at=CURRENT_TIMESTAMP WHERE reservation_id=?",
                bookingId);
        jdbc.update("""
                INSERT INTO bf_meeting_verification_log
                (reservation_id,verification_id,action,operator_id,status_before,status_after,note)
                VALUES (?,?,'COMPLETE',?,'VERIFIED','COMPLETED','会议使用结束')
                """, bookingId, state.verificationId(), operatorId);
        jdbc.update("""
                INSERT INTO bf_meeting_reservation_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'VERIFIED','COMPLETED',?,'会议使用结束',?)
                """, bookingId, operatorId, "MEETING-COMPLETE:" + bookingId);
        jdbc.update("""
                INSERT INTO bf_order_status_log
                (order_id,from_status,to_status,operator_id,reason,idempotency_key)
                VALUES (?,?,'COMPLETED',?,'会议使用结束',?)
                """, state.orderId(), state.orderStatus(), operatorId, "MEETING-ORDER-COMPLETE:" + bookingId);
        audit(operatorId, "MEETING_USAGE_COMPLETE", bookingId, "会议使用结束");
        return usage(bookingId);
    }

    @Transactional(readOnly = true)
    public List<VerificationLogResponse> verificationLogs(AuthenticatedPrincipal principal,
                                                           LocalDate startDate, LocalDate endDate) {
        requireStaff(principal);
        DateRange range = range(startDate, endDate);
        return jdbc.query("""
                SELECT l.id,l.reservation_id,r.booking_no,l.verification_id,l.action,
                       COALESCE(u.real_name,u.nickname) operator_name,l.verification_channel,l.device_no,
                       l.status_before,l.status_after,l.note,l.created_at
                FROM bf_meeting_verification_log l JOIN bf_meeting_reservation r ON r.id=l.reservation_id
                JOIN bf_user u ON u.id=l.operator_id
                WHERE DATE(l.created_at) BETWEEN ? AND ? ORDER BY l.id DESC
                """, (rs,n)->new VerificationLogResponse(rs.getLong("id"),rs.getLong("reservation_id"),
                rs.getString("booking_no"),rs.getLong("verification_id"),rs.getString("action"),
                rs.getString("operator_name"),rs.getString("verification_channel"),rs.getString("device_no"),
                rs.getString("status_before"),rs.getString("status_after"),rs.getString("note"),
                rs.getObject("created_at", LocalDateTime.class)), range.start(), range.end());
    }

    @Transactional(readOnly = true)
    public List<UsageRecordResponse> usageRecords(AuthenticatedPrincipal principal,
                                                  LocalDate startDate, LocalDate endDate) {
        requireStaff(principal);
        DateRange range = range(startDate, endDate);
        return jdbc.queryForList("""
                SELECT r.id FROM bf_meeting_reservation r JOIN bf_meeting_booking u ON u.reservation_id=r.id
                WHERE r.booking_date BETWEEN ? AND ? ORDER BY r.booking_date DESC,r.start_time DESC
                """, Long.class, range.start(), range.end()).stream().map(this::usage).toList();
    }

    private UsageRecordResponse usage(long bookingId) {
        return jdbc.queryForObject("""
                SELECT r.id,r.booking_no,o.order_no,r.room_id,m.name room_name,r.meeting_subject,r.booking_type,
                       r.attendees,r.booking_date,r.start_time,r.end_time,r.status booking_status,
                       u.status usage_status,COALESCE(actor.real_name,actor.nickname) checked_in_by,
                       u.usage_started_at,u.usage_ended_at,u.verification_id
                FROM bf_meeting_reservation r JOIN bf_order o ON o.id=r.order_id
                JOIN bf_meeting_room m ON m.id=r.room_id JOIN bf_meeting_booking u ON u.reservation_id=r.id
                LEFT JOIN bf_user actor ON actor.id=u.checked_in_by WHERE r.id=?
                """, (rs,n)->new UsageRecordResponse(rs.getLong("id"),rs.getString("booking_no"),
                rs.getString("order_no"),rs.getLong("room_id"),rs.getString("room_name"),
                rs.getString("meeting_subject"),rs.getString("booking_type"),rs.getInt("attendees"),
                rs.getObject("booking_date",LocalDate.class),rs.getObject("start_time",java.time.LocalTime.class),
                rs.getObject("end_time",java.time.LocalTime.class),rs.getString("booking_status"),
                rs.getString("usage_status"),rs.getString("checked_in_by"),
                rs.getObject("usage_started_at",LocalDateTime.class),rs.getObject("usage_ended_at",LocalDateTime.class),
                rs.getLong("verification_id")), bookingId);
    }

    private DateRange range(LocalDate start, LocalDate end) {
        LocalDate e = end == null ? LocalDate.now() : end;
        LocalDate s = start == null ? e.minusDays(29) : start;
        if (s.isAfter(e) || s.plusYears(2).isBefore(e)) throw invalid("查询日期范围不合法或超过两年");
        return new DateRange(s,e);
    }
    private void requireStaff(AuthenticatedPrincipal p) {
        if (p == null || (!"ADMIN".equals(p.accountType()) && !"OPS".equals(p.accountType())))
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "仅管理或运营人员可以处理会议核销");
    }
    private long requireUser(AuthenticatedPrincipal p) {
        List<Long> ids=jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());
        if(ids.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"当前工作人员未关联平台用户");
        return ids.get(0);
    }
    private void audit(long actor,String action,long id,String detail){jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?, 'MEETING_RESERVATION',?,JSON_OBJECT('detail',?))",actor,action,String.valueOf(id),detail);}
    private BusinessException invalid(String m){return new BusinessException(CommonErrorCode.INVALID_REQUEST,m);}
    private BusinessException conflict(String m){return new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,m);}
    private record LockedUsage(long reservationId,String type,String reservationStatus,long orderId,long verificationId,String verificationStatus,long orderItemId,String usageStatus){}
    private record CompleteState(long id,long orderId,String status,String orderStatus,long verificationId,String usageStatus){}
    private record DateRange(LocalDate start,LocalDate end){}
}
