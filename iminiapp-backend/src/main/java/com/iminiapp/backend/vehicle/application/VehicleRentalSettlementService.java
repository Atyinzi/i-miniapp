package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.vehicle.api.VehicleReturnRequest;
import com.iminiapp.backend.vehicle.api.VehicleSettlementResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class VehicleRentalSettlementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public VehicleRentalSettlementService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public VehicleSettlementResponse returnVehicle(AuthenticatedPrincipal principal, long dispatchId, VehicleReturnRequest request) {
        validateReturn(request);
        long operator = ensureUser(principal);
        RentalForSettlement rental = lockRental(dispatchId);
        if (!"IN_SERVICE".equals(rental.dispatchStatus())) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "只有使用中的社会租赁车辆可以归还");
        }
        LocalDateTime returnedAt = request.returnedAt() == null ? LocalDateTime.now() : request.returnedAt();
        BigDecimal startMileage = rental.deliveryMileageKm() == null ? rental.vehicleMileageKm() : rental.deliveryMileageKm();
        if (request.endMileageKm().compareTo(startMileage) < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "归还里程不能小于交车里程");
        }
        BigDecimal actualDistance = request.endMileageKm().subtract(startMileage).setScale(2, RoundingMode.HALF_UP);
        int overtimeMinutes = returnedAt.isAfter(rental.plannedReturnAt())
                ? Math.toIntExact(Duration.between(rental.plannedReturnAt(), returnedAt).toMinutes()) : 0;
        int limitKm = distanceLimit(rental);
        BigDecimal overDistance = actualDistance.subtract(BigDecimal.valueOf(limitKm)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        long overtimeAmount = overtimeMinutes == 0 ? 0 : Math.multiplyExact((overtimeMinutes + 59L) / 60L, value(rental.overtimeCentPerHour()));
        long overDistanceAmount = overDistance.multiply(BigDecimal.valueOf(value(rental.overDistanceCentPerKm())))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long extraAmount = Math.addExact(overtimeAmount, overDistanceAmount);
        long deduction = Math.min(extraAmount, rental.depositAmountCent());
        long refund = rental.depositAmountCent() - deduction;
        long supplement = extraAmount - deduction;
        String settlementStatus = supplement > 0 ? "PENDING_SUPPLEMENT" : "SETTLED";
        String orderStatus = supplement > 0 ? "PENDING_SUPPLEMENT" : "COMPLETED";

        upsertHandover(dispatchId, returnedAt, request, startMileage);
        long settlementId = insertSettlement(rental, returnedAt, actualDistance, overtimeMinutes, overDistance,
                overtimeAmount, overDistanceAmount, deduction, refund, supplement, settlementStatus);
        jdbc.update("""
                INSERT INTO bf_vehicle_deposit(order_id,deposit_amount_cent,deducted_amount_cent,refunded_amount_cent,status,refunded_at)
                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE deducted_amount_cent=VALUES(deducted_amount_cent),
                    refunded_amount_cent=VALUES(refunded_amount_cent),status=VALUES(status),refunded_at=CURRENT_TIMESTAMP
                """, rental.orderId(), rental.depositAmountCent(), deduction, refund,
                deduction == 0 ? "REFUNDED" : (refund == 0 ? "DEDUCTED" : "PARTIAL_REFUNDED"));
        jdbc.update("UPDATE bf_vehicle_dispatch SET status='COMPLETED',completed_at=? WHERE id=?", returnedAt, dispatchId);
        jdbc.update("UPDATE bf_vehicle SET status='AVAILABLE',mileage_km=? WHERE id=?", request.endMileageKm(), rental.vehicleId());
        jdbc.update("UPDATE bf_driver SET status='AVAILABLE' WHERE id=?", rental.driverId());
        changeOrderStatus(rental.orderId(), "IN_SERVICE", orderStatus, operator, "车辆归还并生成结算单");
        jdbc.update("INSERT INTO bf_vehicle_trip_log(dispatch_id,event_type,event_at,mileage_km,fuel_liter,detail) VALUES (?,'RETURNED',?,?,?,JSON_OBJECT('condition',?))",
                dispatchId, returnedAt, request.endMileageKm(), request.fuelLiter(), request.condition());
        audit(operator, "VEHICLE_RENTAL_RETURN", "VEHICLE_SETTLEMENT", settlementId,
                "supplementAmountCent=" + supplement + ",depositRefundCent=" + refund);
        return findSettlement(settlementId);
    }

    @Transactional(readOnly = true)
    public VehicleSettlementResponse detailForUser(AuthenticatedPrincipal principal, long orderId) {
        return findByOrder(orderId, requireUser(principal), true);
    }

    private void upsertHandover(long dispatchId, LocalDateTime returnedAt, VehicleReturnRequest request, BigDecimal startMileage) {
        String photos = json(request.photoUrls() == null ? List.of() : request.photoUrls());
        jdbc.update("""
                INSERT INTO bf_vehicle_handover(dispatch_id,delivery_at,delivery_mileage_km,returned_at,return_mileage_km,return_condition,return_photos,status)
                VALUES (?,CURRENT_TIMESTAMP,?,?,?, ?,CAST(? AS JSON),'RETURNED')
                ON DUPLICATE KEY UPDATE returned_at=VALUES(returned_at),return_mileage_km=VALUES(return_mileage_km),
                    return_condition=VALUES(return_condition),return_photos=VALUES(return_photos),status='RETURNED'
                """, dispatchId, startMileage, returnedAt, request.endMileageKm(), optional(request.condition(), 500), photos);
    }

    private long insertSettlement(RentalForSettlement r, LocalDateTime returnedAt, BigDecimal distance, int overtimeMinutes,
                                  BigDecimal overDistance, long overtimeAmount, long distanceAmount,
                                  long deduction, long refund, long supplement, String status) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO bf_vehicle_settlement(order_id,dispatch_id,planned_return_at,actual_return_at,actual_distance_km,
                    overtime_minutes,over_distance_km,overtime_amount_cent,over_distance_amount_cent,deposit_deduction_cent,
                    deposit_refund_cent,supplement_amount_cent,supplement_paid_cent,status,settled_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,CASE WHEN ?='SETTLED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                    """, Statement.RETURN_GENERATED_KEYS);
            s.setLong(1, r.orderId()); s.setLong(2, r.dispatchId()); s.setObject(3, r.plannedReturnAt()); s.setObject(4, returnedAt);
            s.setBigDecimal(5, distance); s.setInt(6, overtimeMinutes); s.setBigDecimal(7, overDistance);
            s.setLong(8, overtimeAmount); s.setLong(9, distanceAmount); s.setLong(10, deduction); s.setLong(11, refund);
            s.setLong(12, supplement); s.setString(13, status); s.setString(14, status); return s;
        }, keys);
        if (keys.getKey() == null) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "结算单创建失败");
        return keys.getKey().longValue();
    }

    private RentalForSettlement lockRental(long dispatchId) {
        List<RentalForSettlement> rows = jdbc.query("""
                SELECT d.id,d.status,d.vehicle_id,d.driver_id,o.id order_id,b.end_at,b.rental_mode,b.deposit_amount_cent,
                       v.mileage_km,h.delivery_mileage_km,t.overtime_cent_per_hour,t.over_distance_cent_per_km,
                       t.half_day_limit_km,t.full_day_limit_km,t.month_limit_km
                FROM bf_vehicle_dispatch d JOIN bf_order_item i ON i.id=d.order_item_id
                JOIN bf_order o ON o.id=i.order_id JOIN bf_vehicle_booking b ON b.order_item_id=i.id
                JOIN bf_vehicle v ON v.id=d.vehicle_id JOIN bf_vehicle_model m ON m.id=v.model_id
                JOIN bf_vehicle_tariff t ON t.model_id=m.id AND t.enabled=1
                LEFT JOIN bf_vehicle_handover h ON h.dispatch_id=d.id
                WHERE d.id=? AND o.order_type='VEHICLE_RENTAL' ORDER BY t.effective_from DESC LIMIT 1 FOR UPDATE
                """, (rs,n) -> new RentalForSettlement(rs.getLong("id"),rs.getString("status"),rs.getLong("vehicle_id"),
                rs.getLong("driver_id"),rs.getLong("order_id"),rs.getObject("end_at",LocalDateTime.class),rs.getString("rental_mode"),
                rs.getLong("deposit_amount_cent"),rs.getBigDecimal("mileage_km"),rs.getBigDecimal("delivery_mileage_km"),
                nullableLong(rs,"overtime_cent_per_hour"),nullableLong(rs,"over_distance_cent_per_km"),
                nullableInt(rs,"half_day_limit_km"),nullableInt(rs,"full_day_limit_km"),nullableInt(rs,"month_limit_km")), dispatchId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "社会租赁派车单不存在");
        return rows.get(0);
    }

    private int distanceLimit(RentalForSettlement r) { return switch (r.rentalMode()) {
        case "HALF_DAY" -> value(r.halfDayLimitKm()); case "FULL_DAY" -> value(r.fullDayLimitKm());
        case "MONTH", "BARE_MONTH" -> value(r.monthLimitKm()); default -> 0; }; }
    private SettlementLocked lockSettlementByOrder(long orderId,long userId){List<SettlementLocked> rows=jdbc.query("SELECT s.id,s.status,s.supplement_amount_cent FROM bf_vehicle_settlement s JOIN bf_order o ON o.id=s.order_id WHERE s.order_id=? AND o.user_id=? FOR UPDATE",(rs,n)->new SettlementLocked(rs.getLong(1),rs.getString(2),rs.getLong(3)),orderId,userId);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"结算单不存在");return rows.get(0);}
    private VehicleSettlementResponse findByOrder(long orderId,long userId,boolean owned){if(owned){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM bf_order WHERE id=? AND user_id=?",Integer.class,orderId,userId);if(count==null||count==0)throw new BusinessException(CommonErrorCode.NOT_FOUND,"结算单不存在");}List<Long> ids=jdbc.queryForList("SELECT id FROM bf_vehicle_settlement WHERE order_id=?",Long.class,orderId);if(ids.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"结算单不存在");return findSettlement(ids.get(0));}
    private VehicleSettlementResponse findSettlement(long id){return jdbc.queryForObject("SELECT id,order_id,dispatch_id,status,planned_return_at,actual_return_at,actual_distance_km,overtime_minutes,over_distance_km,overtime_amount_cent,over_distance_amount_cent,deposit_deduction_cent,deposit_refund_cent,supplement_amount_cent,supplement_paid_cent FROM bf_vehicle_settlement WHERE id=?",(rs,n)->new VehicleSettlementResponse(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getObject(5,LocalDateTime.class),rs.getObject(6,LocalDateTime.class),rs.getBigDecimal(7),rs.getInt(8),rs.getBigDecimal(9),rs.getLong(10),rs.getLong(11),rs.getLong(12),rs.getLong(13),rs.getLong(14),rs.getLong(15)),id);}
    private void changeOrderStatus(long id,String from,String to,long operator,String reason){if(jdbc.update("UPDATE bf_order SET status=?,completed_at=CASE WHEN ?='COMPLETED' THEN CURRENT_TIMESTAMP ELSE completed_at END WHERE id=? AND status=?",to,to,id,from)!=1)throw new BusinessException(CommonErrorCode.CONFLICT,"订单状态已变化");jdbc.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,?,?,?,?)",id,from,to,operator,reason,"VEHICLE-RETURN:"+id);}
    private void audit(long operator,String action,String type,long target,String detail){jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,?,?,JSON_OBJECT('detail',?))",operator,action,type,String.valueOf(target),detail);}
    private long ensureUser(AuthenticatedPrincipal p){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());}
    private long requireUser(AuthenticatedPrincipal p){List<Long> ids=jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());if(ids.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"用户不存在");return ids.get(0);}
    private void validateReturn(VehicleReturnRequest r){if(r==null||r.endMileageKm()==null||r.endMileageKm().compareTo(BigDecimal.ZERO)<0)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"endMileageKm 必须为非负数");if(r.fuelLiter()!=null&&r.fuelLiter().compareTo(BigDecimal.ZERO)<0)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"fuelLiter 不能为负数");optional(r.condition(),500);if(r.photoUrls()!=null&&(r.photoUrls().size()>10||r.photoUrls().stream().anyMatch(url->!StringUtils.hasText(url)||url.length()>512)))throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"归还照片最多10张且URL最长512字符");}
    private String optional(String v,int max){if(!StringUtils.hasText(v))return null;if(v.trim().length()>max)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"字段最长"+max+"字符");return v.trim();}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(JacksonException e){throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"照片数据不正确");}}
    private long value(Long v){return v==null?0:v;} private int value(Integer v){return v==null?0:v;}
    private Long nullableLong(java.sql.ResultSet rs,String c)throws java.sql.SQLException{long v=rs.getLong(c);return rs.wasNull()?null:v;}
    private Integer nullableInt(java.sql.ResultSet rs,String c)throws java.sql.SQLException{int v=rs.getInt(c);return rs.wasNull()?null:v;}
    private record RentalForSettlement(long dispatchId,String dispatchStatus,long vehicleId,long driverId,long orderId,LocalDateTime plannedReturnAt,String rentalMode,long depositAmountCent,BigDecimal vehicleMileageKm,BigDecimal deliveryMileageKm,Long overtimeCentPerHour,Long overDistanceCentPerKm,Integer halfDayLimitKm,Integer fullDayLimitKm,Integer monthLimitKm){}
    private record SettlementLocked(long id,String status,long supplementAmountCent){}
}
