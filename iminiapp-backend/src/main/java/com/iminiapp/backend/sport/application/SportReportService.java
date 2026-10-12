package com.iminiapp.backend.sport.application;

import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.sport.api.SportReportModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class SportReportService {
    private static final int MAX_RANGE_DAYS=366;
    private static final Set<String> DIMENSIONS=Set.of("VENUE","COURT_TYPE","COURT","TIME_SLOT");
    private final JdbcTemplate jdbc;
    public SportReportService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public SportReportOverviewResponse overview(LocalDate requestedStart,LocalDate requestedEnd,Long venueId){
        Period p=period(requestedStart,requestedEnd); Where w=bookingWhere(p,venueId);
        long bookings=count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_sport_court c ON c.id=b.court_id"+w.sql(),w.args());
        long paid=count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND EXISTS(SELECT 1 FROM bf_payment p WHERE p.order_id=b.order_id AND p.status='SUCCESS')",w.args());
        long completed=count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND b.status='COMPLETED'",w.args());
        long cancelled=count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND b.status='CANCELLED'",w.args());
        long refunded=count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND b.status='REFUNDED'",w.args());
        long gross=count("SELECT COALESCE(SUM(p.amount_cent),0) FROM bf_payment p JOIN bf_sport_booking b ON b.order_id=p.order_id JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND p.status='SUCCESS'",w.args());
        long refunds=count("SELECT COALESCE(SUM(r.amount_cent),0) FROM bf_refund r JOIN bf_sport_booking b ON b.order_id=r.order_id JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND r.status='SUCCESS'",w.args());
        long verifications=count("SELECT COUNT(*) FROM bf_sport_verification_log x JOIN bf_sport_booking b ON b.id=x.booking_id JOIN bf_sport_court c ON c.id=b.court_id"+w.sql(),w.args());
        long memberBookings=count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND b.member_account_id IS NOT NULL",w.args());
        long memberPaid=count("SELECT COALESCE(SUM(p.amount_cent),0) FROM bf_payment p JOIN bf_sport_booking b ON b.order_id=p.order_id JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND b.member_account_id IS NOT NULL AND p.status='SUCCESS'",w.args());
        long memberRefund=count("SELECT COALESCE(SUM(r.amount_cent),0) FROM bf_refund r JOIN bf_sport_booking b ON b.order_id=r.order_id JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+" AND b.member_account_id IS NOT NULL AND r.status='SUCCESS'",w.args());
        long memberConsumption=Math.max(0,memberPaid-memberRefund);
        List<SportUtilizationResponse> util=utilization(p.start(),p.end(),venueId,"VENUE");
        long capacity=util.stream().mapToLong(SportUtilizationResponse::capacitySlots).sum();
        long occupied=util.stream().mapToLong(SportUtilizationResponse::occupiedSlots).sum();
        return new SportReportOverviewResponse(p.start(),p.end(),venueId,bookings,paid,completed,cancelled,refunded,
                gross,refunds,Math.max(0,gross-refunds),verifications,percent(verifications,paid),memberBookings,
                memberConsumption,capacity,occupied,percent(occupied,capacity),LocalDateTime.now());
    }

    public List<SportUtilizationResponse> utilization(LocalDate requestedStart,LocalDate requestedEnd,
                                                       Long venueId,String requestedDimension){
        Period p=period(requestedStart,requestedEnd);String dimension=StringUtils.hasText(requestedDimension)?requestedDimension.trim().toUpperCase(Locale.ROOT):"COURT";
        if(!DIMENSIONS.contains(dimension))throw invalid("dimension仅支持VENUE、COURT_TYPE、COURT或TIME_SLOT");
        String venueClause=venueId==null?"":" AND v.id=?";Object[] venueArgs=venueId==null?new Object[]{}:new Object[]{venueId};
        List<OpeningRule> rules=jdbc.query("""
                SELECT r.court_id,r.day_of_week,r.start_time,r.end_time,r.slot_minutes,
                v.id venue_id,v.name venue_name,t.id type_id,t.name type_name,c.name court_name
                FROM bf_sport_opening_rule r JOIN bf_sport_court c ON c.id=r.court_id
                JOIN bf_sport_venue v ON v.id=c.venue_id JOIN bf_sport_court_type t ON t.id=c.court_type_id
                WHERE r.status='ACTIVE' AND c.status='ACTIVE' AND v.status='ACTIVE' AND t.status='ACTIVE'
                """+venueClause,(rs,n)->new OpeningRule(rs.getLong("court_id"),rs.getInt("day_of_week"),
                rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class),rs.getInt("slot_minutes"),
                rs.getLong("venue_id"),rs.getString("venue_name"),rs.getLong("type_id"),rs.getString("type_name"),rs.getString("court_name")),venueArgs);
        List<Closure> closures=jdbc.query("""
                SELECT x.court_id,x.closure_date,x.start_time,x.end_time FROM bf_sport_closure x
                JOIN bf_sport_court c ON c.id=x.court_id JOIN bf_sport_venue v ON v.id=c.venue_id
                WHERE x.status='ACTIVE' AND x.closure_date BETWEEN ? AND ?
                """+venueClause,(rs,n)->new Closure(rs.getLong("court_id"),rs.getObject("closure_date",LocalDate.class),
                rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class)),concat(new Object[]{p.start(),p.end()},venueArgs));
        Map<Key,MutableUtil> values=new LinkedHashMap<>();
        for(LocalDate day=p.start();!day.isAfter(p.end());day=day.plusDays(1)){
            int dow=day.getDayOfWeek().getValue();
            for(OpeningRule rule:rules){if(rule.day()!=dow)continue;
                for(LocalTime start=rule.start();start.isBefore(rule.end());start=start.plusMinutes(rule.minutes())){
                    LocalTime end=start.plusMinutes(rule.minutes());if(end.isAfter(rule.end())||closed(closures,rule.courtId(),day,start,end))continue;
                    Key key=key(dimension,rule,start,end);values.computeIfAbsent(key,k->new MutableUtil()).capacity++;
                }
            }
        }
        List<Occupied> occupied=jdbc.query("""
                SELECT b.court_id,b.start_time,b.end_time,v.id venue_id,v.name venue_name,
                t.id type_id,t.name type_name,c.name court_name FROM bf_sport_booking b
                JOIN bf_sport_court c ON c.id=b.court_id JOIN bf_sport_venue v ON v.id=c.venue_id
                JOIN bf_sport_court_type t ON t.id=c.court_type_id
                WHERE b.booking_date BETWEEN ? AND ? AND b.status IN ('CONFIRMED','IN_USE','COMPLETED')
                """+venueClause,(rs,n)->new Occupied(rs.getLong("court_id"),rs.getObject("start_time",LocalTime.class),
                rs.getObject("end_time",LocalTime.class),rs.getLong("venue_id"),rs.getString("venue_name"),
                rs.getLong("type_id"),rs.getString("type_name"),rs.getString("court_name")),concat(new Object[]{p.start(),p.end()},venueArgs));
        for(Occupied item:occupied){Key key=key(dimension,item,item.start(),item.end());values.computeIfAbsent(key,k->new MutableUtil()).occupied++;}
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->new SportUtilizationResponse(
                dimension,e.getKey().code(),e.getKey().name(),e.getValue().capacity,e.getValue().occupied,
                percent(e.getValue().occupied,e.getValue().capacity))).toList();
    }

    public PageResponse<SportUsageDetailResponse> usageDetails(LocalDate requestedStart,LocalDate requestedEnd,
            Long venueId,String status,int page,int pageSize){
        validatePage(page,pageSize);Period p=period(requestedStart,requestedEnd);Where w=bookingWhere(p,venueId);
        List<Object> args=new ArrayList<>(Arrays.asList(w.args()));String statusClause="";
        if(StringUtils.hasText(status)){statusClause=" AND b.status=?";args.add(status.trim().toUpperCase(Locale.ROOT));}
        long total=count("SELECT COUNT(*) FROM bf_sport_booking b JOIN bf_sport_court c ON c.id=b.court_id"+w.sql()+statusClause,args.toArray());
        args.add(pageSize);args.add((page-1)*pageSize);
        List<SportUsageDetailResponse> items=jdbc.query(detailSelect()+w.sql()+statusClause+" ORDER BY b.booking_date DESC,b.start_time DESC,b.id DESC LIMIT ? OFFSET ?",
                (rs,n)->detail(rs),args.toArray());
        return new PageResponse<>(items,page,pageSize,total,total==0?0:(int)((total+pageSize-1)/pageSize));
    }

    public byte[] exportUsageCsv(LocalDate start,LocalDate end,Long venueId,String status){
        Period p=period(start,end);Where w=bookingWhere(p,venueId);List<Object> args=new ArrayList<>(Arrays.asList(w.args()));String statusClause="";
        if(StringUtils.hasText(status)){statusClause=" AND b.status=?";args.add(status.trim().toUpperCase(Locale.ROOT));}
        List<SportUsageDetailResponse> rows=jdbc.query(detailSelect()+w.sql()+statusClause+" ORDER BY b.booking_date,b.start_time,b.id",(rs,n)->detail(rs),args.toArray());
        if(rows.size()>50_000)throw invalid("单次CSV导出最多50000条，请缩小日期范围");
        StringBuilder out=new StringBuilder("\uFEFF订单号,场馆,场地类型,场地,预订日期,开始时间,结束时间,预订状态,订单状态,原价(分),实付(分),退款(分),会员等级,支付渠道,核销时间,完成时间,核销人员,核销设备\r\n");
        for(SportUsageDetailResponse r:rows){out.append(csv(r.orderNo())).append(',').append(csv(r.venueName())).append(',').append(csv(r.courtTypeName())).append(',').append(csv(r.courtName())).append(',').append(r.bookingDate()).append(',').append(r.startTime()).append(',').append(r.endTime()).append(',').append(r.bookingStatus()).append(',').append(r.orderStatus()).append(',').append(r.originalPriceCent()).append(',').append(r.paidAmountCent()).append(',').append(r.refundAmountCent()).append(',').append(csv(r.memberLevelCode())).append(',').append(csv(r.paymentChannel())).append(',').append(nullable(r.checkedInAt())).append(',').append(nullable(r.completedAt())).append(',').append(csv(r.verificationOperator())).append(',').append(csv(r.verificationDeviceNo())).append("\r\n");}
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String detailSelect(){return """
            SELECT b.id booking_id,b.order_id,o.order_no,v.id venue_id,v.name venue_name,
            t.id type_id,t.name type_name,c.id court_id,c.name court_name,b.booking_date,b.start_time,b.end_time,
            b.status booking_status,o.status order_status,b.original_price_cent,o.paid_amount_cent,
            COALESCE((SELECT SUM(r.amount_cent) FROM bf_refund r WHERE r.order_id=o.id AND r.status='SUCCESS'),0) refund_amount_cent,
            b.member_level_code,(SELECT p.channel FROM bf_payment p WHERE p.order_id=o.id AND p.status='SUCCESS' ORDER BY p.id LIMIT 1) payment_channel,
            b.checked_in_at,b.completed_at,(SELECT u.nickname FROM bf_sport_verification_log x JOIN bf_user u ON u.id=x.operator_id WHERE x.booking_id=b.id ORDER BY x.id LIMIT 1) verification_operator,
            (SELECT x.device_no FROM bf_sport_verification_log x WHERE x.booking_id=b.id ORDER BY x.id LIMIT 1) verification_device
            FROM bf_sport_booking b JOIN bf_order o ON o.id=b.order_id JOIN bf_sport_court c ON c.id=b.court_id
            JOIN bf_sport_venue v ON v.id=c.venue_id JOIN bf_sport_court_type t ON t.id=c.court_type_id
            """;}
    private SportUsageDetailResponse detail(java.sql.ResultSet rs)throws java.sql.SQLException{return new SportUsageDetailResponse(rs.getLong("booking_id"),rs.getLong("order_id"),rs.getString("order_no"),rs.getLong("venue_id"),rs.getString("venue_name"),rs.getLong("type_id"),rs.getString("type_name"),rs.getLong("court_id"),rs.getString("court_name"),rs.getObject("booking_date",LocalDate.class),rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class),rs.getString("booking_status"),rs.getString("order_status"),rs.getLong("original_price_cent"),rs.getLong("paid_amount_cent"),rs.getLong("refund_amount_cent"),rs.getString("member_level_code"),rs.getString("payment_channel"),rs.getObject("checked_in_at",LocalDateTime.class),rs.getObject("completed_at",LocalDateTime.class),rs.getString("verification_operator"),rs.getString("verification_device"));}
    private Where bookingWhere(Period p,Long venueId){return venueId==null?new Where(" WHERE b.booking_date BETWEEN ? AND ?",new Object[]{p.start(),p.end()}):new Where(" WHERE b.booking_date BETWEEN ? AND ? AND c.venue_id=?",new Object[]{p.start(),p.end(),venueId});}
    private Key key(String dimension,DimensionData d,LocalTime start,LocalTime end){return switch(dimension){case"VENUE"->new Key(String.valueOf(d.venueId()),d.venueName());case"COURT_TYPE"->new Key(String.valueOf(d.typeId()),d.typeName());case"COURT"->new Key(String.valueOf(d.courtId()),d.courtName());default->new Key(start+"-"+end,start+"—"+end);};}
    private boolean closed(List<Closure> values,long courtId,LocalDate date,LocalTime start,LocalTime end){return values.stream().anyMatch(x->x.courtId()==courtId&&x.date().equals(date)&&x.start().isBefore(end)&&x.end().isAfter(start));}
    private long count(String sql,Object...args){Long v=jdbc.queryForObject(sql,Long.class,args);return v==null?0:v;}
    private BigDecimal percent(long numerator,long denominator){return denominator==0?BigDecimal.ZERO.setScale(2):BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP);}
    private Period period(LocalDate s,LocalDate e){LocalDate end=e==null?LocalDate.now():e;LocalDate start=s==null?end.minusDays(29):s;if(start.isAfter(end))throw invalid("开始日期不能晚于结束日期");if(ChronoUnit.DAYS.between(start,end)+1>MAX_RANGE_DAYS)throw invalid("报表日期范围不能超过366天");return new Period(start,end);}
    private void validatePage(int p,int s){if(p<1||s<1||s>100)throw invalid("page从1开始，pageSize范围为1—100");}
    private String csv(Object value){if(value==null)return"";String s=String.valueOf(value);return(s.contains(",")||s.contains("\"")||s.contains("\n")||s.contains("\r"))?"\""+s.replace("\"","\"\"")+"\"":s;}
    private String nullable(Object v){return v==null?"":String.valueOf(v);}
    private Object[] concat(Object[] a,Object[] b){Object[] r=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,r,a.length,b.length);return r;}
    private BusinessException invalid(String m){return new BusinessException(CommonErrorCode.INVALID_REQUEST,m);}
    private interface DimensionData{long courtId();long venueId();String venueName();long typeId();String typeName();String courtName();}
    private record OpeningRule(long courtId,int day,LocalTime start,LocalTime end,int minutes,long venueId,String venueName,long typeId,String typeName,String courtName)implements DimensionData{}
    private record Occupied(long courtId,LocalTime start,LocalTime end,long venueId,String venueName,long typeId,String typeName,String courtName)implements DimensionData{}
    private record Closure(long courtId,LocalDate date,LocalTime start,LocalTime end){}
    private record Key(String code,String name)implements Comparable<Key>{public int compareTo(Key x){return code.compareTo(x.code);}}
    private static final class MutableUtil{long capacity;long occupied;}
    private record Period(LocalDate start,LocalDate end){}
    private record Where(String sql,Object[] args){}
}
