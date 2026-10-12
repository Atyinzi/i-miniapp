package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.vehicle.api.VehicleReportOverviewResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class VehicleReportService {
    private final JdbcTemplate jdbc;
    public VehicleReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public VehicleReportOverviewResponse overview(LocalDate startDate, LocalDate endDate) {
        Range range = range(startDate, endDate);
        long internal = count("SELECT COUNT(*) FROM bf_order WHERE order_type='VEHICLE_INTERNAL' AND created_at>=? AND created_at<?", range);
        long approved = count("SELECT COUNT(*) FROM bf_vehicle_application_review WHERE decision='APPROVED' AND created_at>=? AND created_at<?", range);
        long rejected = count("SELECT COUNT(*) FROM bf_vehicle_application_review WHERE decision='REJECTED' AND created_at>=? AND created_at<?", range);
        long dispatches = count("SELECT COUNT(*) FROM bf_vehicle_dispatch WHERE created_at>=? AND created_at<?", range);
        long completed = count("SELECT COUNT(*) FROM bf_vehicle_dispatch WHERE status='COMPLETED' AND completed_at>=? AND completed_at<?", range);
        BigDecimal distance = decimal("SELECT COALESCE(SUM(actual_distance_km),0) FROM bf_vehicle_settlement WHERE actual_return_at>=? AND actual_return_at<?", range);
        BigDecimal fuel = decimal("SELECT COALESCE(SUM(fuel_liter),0) FROM bf_vehicle_trip_log WHERE event_type IN ('COMPLETED','RETURNED') AND event_at>=? AND event_at<?", range);
        long rentals = count("SELECT COUNT(*) FROM bf_order WHERE order_type='VEHICLE_RENTAL' AND created_at>=? AND created_at<?", range);
        long paid = amount("SELECT COALESCE(SUM(paid_amount_cent),0) FROM bf_order WHERE order_type='VEHICLE_RENTAL' AND created_at>=? AND created_at<?", range);
        long deposit = amount("SELECT COALESCE(SUM(deposit_amount_cent),0) FROM bf_vehicle_deposit WHERE created_at>=? AND created_at<?", range);
        long deducted = amount("SELECT COALESCE(SUM(deducted_amount_cent),0) FROM bf_vehicle_deposit WHERE created_at>=? AND created_at<?", range);
        long refunded = amount("SELECT COALESCE(SUM(refunded_amount_cent),0) FROM bf_vehicle_deposit WHERE created_at>=? AND created_at<?", range);
        long supplement = amount("SELECT COALESCE(SUM(supplement_amount_cent),0) FROM bf_vehicle_settlement WHERE created_at>=? AND created_at<?", range);
        return new VehicleReportOverviewResponse(range.startDate(),range.endDate(),internal,approved,rejected,dispatches,
                completed,distance,fuel,rentals,paid,deposit,deducted,refunded,supplement);
    }

    @Transactional(readOnly = true)
    public byte[] csv(LocalDate startDate, LocalDate endDate) {
        Range range=range(startDate,endDate); StringBuilder out=new StringBuilder("\uFEFF订单号,业务类型,订单状态,申请/下单时间,出发地,目的地,车辆编号,车牌号,司机编号,调度状态,行驶里程公里,油耗升,押金扣除分,押金退还分,补款分\r\n");
        for(ReportRow r:rows(range)){out.append(cell(r.orderNo())).append(',').append(cell(r.businessMode())).append(',').append(cell(r.orderStatus())).append(',').append(cell(String.valueOf(r.createdAt()))).append(',').append(cell(r.departure())).append(',').append(cell(r.destination())).append(',').append(cell(r.vehicleNo())).append(',').append(cell(r.plateNo())).append(',').append(cell(r.driverNo())).append(',').append(cell(r.dispatchStatus())).append(',').append(r.distance()).append(',').append(r.fuel()).append(',').append(r.deduction()).append(',').append(r.refund()).append(',').append(r.supplement()).append("\r\n");}
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public String printableHtml(LocalDate startDate,LocalDate endDate){VehicleReportOverviewResponse o=overview(startDate,endDate);StringBuilder h=new StringBuilder("<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><title>车辆运营报表</title><style>body{font-family:sans-serif;padding:24px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #999;padding:6px}@media print{button{display:none}}</style></head><body><button onclick='window.print()'>打印</button><h1>车辆运营报表</h1><p>").append(o.startDate()).append(" 至 ").append(o.endDate()).append("</p><p>内部申请：").append(o.internalApplications()).append("，社会租赁订单：").append(o.socialRentalOrders()).append("，完成行程：").append(o.completedTripCount()).append("，总油耗：").append(o.totalFuelLiter()).append("升</p><table><tr><th>订单号</th><th>类型</th><th>状态</th><th>车辆</th><th>司机</th><th>里程(km)</th><th>油耗(L)</th><th>押金退还(分)</th></tr>");for(ReportRow r:rows(range(startDate,endDate))){h.append("<tr><td>").append(html(r.orderNo())).append("</td><td>").append(html(r.businessMode())).append("</td><td>").append(html(r.orderStatus())).append("</td><td>").append(html(r.plateNo())).append("</td><td>").append(html(r.driverNo())).append("</td><td>").append(r.distance()).append("</td><td>").append(r.fuel()).append("</td><td>").append(r.refund()).append("</td></tr>");}return h.append("</table></body></html>").toString();}

    private List<ReportRow> rows(Range r){return jdbc.query("""
            SELECT o.order_no,b.business_mode,o.status,o.created_at,b.departure,b.destination,
                   COALESCE(v.vehicle_no,''),COALESCE(v.plate_no,''),COALESCE(dr.driver_no,''),COALESCE(d.status,''),
                   COALESCE(s.actual_distance_km,0),COALESCE((SELECT SUM(l.fuel_liter) FROM bf_vehicle_trip_log l WHERE l.dispatch_id=d.id),0),
                   COALESCE(s.deposit_deduction_cent,0),COALESCE(s.deposit_refund_cent,0),COALESCE(s.supplement_amount_cent,0)
            FROM bf_order o JOIN bf_order_item i ON i.order_id=o.id JOIN bf_vehicle_booking b ON b.order_item_id=i.id
            LEFT JOIN bf_vehicle_dispatch d ON d.order_item_id=i.id LEFT JOIN bf_vehicle v ON v.id=d.vehicle_id
            LEFT JOIN bf_driver dr ON dr.id=d.driver_id LEFT JOIN bf_vehicle_settlement s ON s.order_id=o.id
            WHERE o.created_at>=? AND o.created_at<? ORDER BY o.id DESC
            """,(rs,n)->new ReportRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,LocalDateTime.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getBigDecimal(11),rs.getBigDecimal(12),rs.getLong(13),rs.getLong(14),rs.getLong(15)),r.start(),r.endExclusive());}
    private Range range(LocalDate s,LocalDate e){if(s==null||e==null||e.isBefore(s)||ChronoUnit.DAYS.between(s,e)>366)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"日期范围不正确且最长366天");return new Range(s,e,s.atStartOfDay(),e.plusDays(1).atStartOfDay());}
    private long count(String sql,Range r){Long v=jdbc.queryForObject(sql,Long.class,r.start(),r.endExclusive());return v==null?0:v;} private long amount(String sql,Range r){return count(sql,r);} private BigDecimal decimal(String sql,Range r){BigDecimal v=jdbc.queryForObject(sql,BigDecimal.class,r.start(),r.endExclusive());return v==null?BigDecimal.ZERO:v;}
    private String cell(String v){String x=v==null?"":v;return '"'+x.replace("\"","\"\"")+'"';} private String html(String v){return (v==null?"":v).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private record Range(LocalDate startDate,LocalDate endDate,LocalDateTime start,LocalDateTime endExclusive){}
    private record ReportRow(String orderNo,String businessMode,String orderStatus,LocalDateTime createdAt,String departure,String destination,String vehicleNo,String plateNo,String driverNo,String dispatchStatus,BigDecimal distance,BigDecimal fuel,long deduction,long refund,long supplement){}
}
