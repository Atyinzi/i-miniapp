package com.iminiapp.backend.sport.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.sport.api.SportVerificationModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class SportVerificationService {
    private final JdbcTemplate jdbc;
    public SportVerificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public SportUsageResponse complete(AuthenticatedPrincipal principal, long orderId) {
        long operatorId = ensureUser(principal);
        Usage usage = lockUsage(orderId);
        if ("COMPLETED".equals(usage.bookingStatus())) return usage(orderId);
        if (!"IN_USE".equals(usage.bookingStatus())) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,
                    "只有使用中的体育预订可以结束，当前状态为 " + usage.bookingStatus());
        }
        if (jdbc.update("UPDATE bf_sport_booking SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=? AND status='IN_USE'", usage.bookingId()) != 1)
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "场地使用状态已变化，请刷新后重试");
        if (jdbc.update("UPDATE bf_order SET status='COMPLETED' WHERE id=? AND status IN ('PAID','READY')", orderId) != 1)
            throw new BusinessException(CommonErrorCode.ORDER_CONFLICT, "订单状态不允许结束场地使用");
        jdbc.update("""
                INSERT INTO bf_sport_booking_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'IN_USE','COMPLETED',?,'工作人员确认场地使用完成',?)
                """, usage.bookingId(), operatorId, "SPORT-COMPLETE:" + usage.bookingId());
        jdbc.update("""
                INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key)
                VALUES (?,?,'COMPLETED',?,'体育场地使用完成',?)
                """, orderId, usage.orderStatus(), operatorId, "SPORT-ORDER-COMPLETE:" + orderId);
        jdbc.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,'SPORT_USAGE_COMPLETE','SPORT_BOOKING',?,JSON_OBJECT('orderId',?))
                """, operatorId, String.valueOf(usage.bookingId()), orderId);
        return usage(orderId);
    }

    @Transactional(readOnly = true)
    public PageResponse<SportVerificationRecordResponse> records(Long venueId, LocalDate bookingDate,
                                                                 int page, int pageSize) {
        validatePage(page,pageSize); StringBuilder where=new StringBuilder(" WHERE 1=1"); List<Object> args=new ArrayList<>();
        if(venueId!=null){where.append(" AND v.id=?");args.add(venueId);}
        if(bookingDate!=null){where.append(" AND b.booking_date=?");args.add(bookingDate);}
        Long total=jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_sport_verification_log x
                JOIN bf_sport_booking b ON b.id=x.booking_id JOIN bf_sport_court c ON c.id=b.court_id
                JOIN bf_sport_venue v ON v.id=c.venue_id
                """+where,Long.class,args.toArray());
        List<Object> paged=new ArrayList<>(args);paged.add(pageSize);paged.add((page-1)*pageSize);
        List<SportVerificationRecordResponse> items=jdbc.query("""
                SELECT x.id,x.booking_id,b.order_id,o.order_no,v.id venue_id,v.name venue_name,
                c.id court_id,c.name court_name,b.booking_date,b.start_time,b.end_time,
                x.verification_id,x.operator_id,u.nickname operator_name,x.verification_channel,x.device_no,
                x.booking_status_before,x.booking_status_after,x.verified_at
                FROM bf_sport_verification_log x JOIN bf_sport_booking b ON b.id=x.booking_id
                JOIN bf_order o ON o.id=b.order_id JOIN bf_sport_court c ON c.id=b.court_id
                JOIN bf_sport_venue v ON v.id=c.venue_id JOIN bf_user u ON u.id=x.operator_id
                """+where+" ORDER BY x.id DESC LIMIT ? OFFSET ?",(rs,n)->new SportVerificationRecordResponse(
                rs.getLong("id"),rs.getLong("booking_id"),rs.getLong("order_id"),rs.getString("order_no"),
                rs.getLong("venue_id"),rs.getString("venue_name"),rs.getLong("court_id"),rs.getString("court_name"),
                rs.getObject("booking_date",LocalDate.class),rs.getObject("start_time",LocalTime.class),
                rs.getObject("end_time",LocalTime.class),rs.getLong("verification_id"),rs.getLong("operator_id"),
                rs.getString("operator_name"),rs.getString("verification_channel"),rs.getString("device_no"),
                rs.getString("booking_status_before"),rs.getString("booking_status_after"),
                rs.getObject("verified_at",LocalDateTime.class)),paged.toArray());
        long count=total==null?0:total;return new PageResponse<>(items,page,pageSize,count,count==0?0:(int)((count+pageSize-1)/pageSize));
    }

    private Usage lockUsage(long orderId){List<Usage> rows=jdbc.query("""
            SELECT b.id booking_id,b.order_id,o.order_no,o.status order_status,b.status booking_status
            FROM bf_sport_booking b JOIN bf_order o ON o.id=b.order_id WHERE b.order_id=? FOR UPDATE
            """,(rs,n)->new Usage(rs.getLong("booking_id"),rs.getLong("order_id"),rs.getString("order_no"),rs.getString("order_status"),rs.getString("booking_status")),orderId);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"体育预订不存在");return rows.get(0);}
    private SportUsageResponse usage(long orderId){return jdbc.queryForObject("""
            SELECT b.id booking_id,b.order_id,o.order_no,o.status order_status,b.status booking_status,
            b.checked_in_at,b.completed_at,b.verification_id FROM bf_sport_booking b
            JOIN bf_order o ON o.id=b.order_id WHERE b.order_id=?
            """,(rs,n)->new SportUsageResponse(rs.getLong("booking_id"),rs.getLong("order_id"),rs.getString("order_no"),rs.getString("order_status"),rs.getString("booking_status"),rs.getObject("checked_in_at",LocalDateTime.class),rs.getObject("completed_at",LocalDateTime.class),rs.getObject("verification_id",Long.class)),orderId);}
    private long ensureUser(AuthenticatedPrincipal p){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());}
    private void validatePage(int p,int s){if(p<1||s<1||s>100)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"page从1开始，pageSize范围为1—100");}
    private record Usage(long bookingId,long orderId,String orderNo,String orderStatus,String bookingStatus){}
}
