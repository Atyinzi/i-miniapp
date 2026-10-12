package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.vehicle.api.VehicleDispatchRequest;
import com.iminiapp.backend.vehicle.api.VehicleRentalDispatchResponse;
import com.iminiapp.backend.vehicle.api.VehicleTripCompleteRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** 已支付社会租赁订单的派车、接单、出车与还车完成。 */
@Service
public class VehicleRentalDispatchService {
    private static final String ORDER_TYPE = "VEHICLE_RENTAL";
    private final JdbcTemplate jdbcTemplate;
    public VehicleRentalDispatchService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Transactional
    public VehicleRentalDispatchResponse dispatch(AuthenticatedPrincipal principal, long orderId, VehicleDispatchRequest request) {
        if (request == null || request.vehicleId() == null || request.driverId() == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "vehicleId 和 driverId 不能为空");
        long operator = ensureUser(principal);
        RentalLocked rental = lockRental(orderId);
        if (!"PAID".equals(rental.orderStatus())) throw new BusinessException(CommonErrorCode.CONFLICT, "只有已支付的社会租赁订单可以派车");
        VehicleLocked vehicle = lockVehicle(request.vehicleId());
        if (!"AVAILABLE".equals(vehicle.status()) || vehicle.modelId() != rental.modelId()) throw new BusinessException(CommonErrorCode.CONFLICT, "请选择同车型且可用的车辆");
        Integer unavailable = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_vehicle_unavailable_period WHERE vehicle_id=? AND status='ACTIVE' AND start_at<? AND end_at>?", Integer.class, vehicle.id(), rental.endAt(), rental.startAt());
        if (unavailable != null && unavailable > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "车辆在租赁时段内维修、保养或被锁定");
        if (vehicle.seatCount() < rental.passengerCount()) throw new BusinessException(CommonErrorCode.CONFLICT, "车辆座位数不足");
        DriverLocked driver = lockDriver(request.driverId());
        if (!"AVAILABLE".equals(driver.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "司机当前不可派遣");
        Integer conflicts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_vehicle_dispatch d JOIN bf_vehicle_booking b ON b.order_item_id=d.order_item_id
                WHERE d.vehicle_id=? AND d.status IN ('WAITING_DRIVER','ACCEPTED','IN_SERVICE')
                  AND b.start_at<? AND b.end_at>?
                """, Integer.class, vehicle.id(), rental.endAt(), rental.startAt());
        if (conflicts != null && conflicts > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "车辆在该租赁时段已有派车任务");
        long dispatchId = insertDispatch(rental.itemId(), vehicle.id(), driver.id());
        jdbcTemplate.update("UPDATE bf_vehicle SET status='DISPATCHED' WHERE id=?", vehicle.id());
        jdbcTemplate.update("UPDATE bf_driver SET status='BUSY' WHERE id=?", driver.id());
        changeOrderStatus(orderId, "PAID", "WAITING_DRIVER", operator, "社会租赁已派车");
        tripLog(dispatchId, "DISPATCHED", null, null, "社会租赁管理员派车");
        audit(operator, "VEHICLE_RENTAL_DISPATCH", dispatchId, "orderId=" + orderId);
        return find(dispatchId);
    }

    @Transactional
    public VehicleRentalDispatchResponse accept(AuthenticatedPrincipal principal, long dispatchId) { return transition(principal, dispatchId, "WAITING_DRIVER", "ACCEPTED", "司机接单"); }
    @Transactional
    public VehicleRentalDispatchResponse depart(AuthenticatedPrincipal principal, long dispatchId) { return transition(principal, dispatchId, "ACCEPTED", "IN_SERVICE", "车辆出发"); }

    @Transactional
    public VehicleRentalDispatchResponse complete(AuthenticatedPrincipal principal, long dispatchId, VehicleTripCompleteRequest request) {
        if (request == null || request.endMileageKm() == null || request.endMileageKm().compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "endMileageKm 必须为非负数");
        if (request.fuelLiter() != null && request.fuelLiter().compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "fuelLiter 不能为负数");
        long operator = ensureUser(principal);
        DispatchLocked dispatch = lockDispatch(dispatchId);
        if (!"IN_SERVICE".equals(dispatch.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "只有行程中的派车单可以完成");
        if (request.endMileageKm().compareTo(dispatch.vehicleMileageKm()) < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "结束里程不能小于当前车辆里程");
        jdbcTemplate.update("UPDATE bf_vehicle_dispatch SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=?", dispatchId);
        jdbcTemplate.update("UPDATE bf_vehicle SET status='AVAILABLE',mileage_km=? WHERE id=?", request.endMileageKm(), dispatch.vehicleId());
        jdbcTemplate.update("UPDATE bf_driver SET status='AVAILABLE' WHERE id=?", dispatch.driverId());
        changeOrderStatus(dispatch.orderId(), "IN_SERVICE", "COMPLETED", operator, "社会租赁行程完成");
        tripLog(dispatchId, "COMPLETED", request.endMileageKm(), request.fuelLiter(), request.remark());
        audit(operator, "VEHICLE_RENTAL_COMPLETE", dispatchId, request.remark());
        return find(dispatchId);
    }

    @Transactional(readOnly = true)
    public List<VehicleRentalDispatchResponse> list(AuthenticatedPrincipal principal, String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        String sql = """
                SELECT d.id,o.id,o.order_no,o.status,v.id,v.vehicle_no,v.plate_no,
                       dr.id,dr.driver_no,d.status,d.accepted_at,d.departed_at,d.completed_at
                FROM bf_vehicle_dispatch d
                JOIN bf_order_item i ON i.id=d.order_item_id
                JOIN bf_order o ON o.id=i.order_id
                JOIN bf_vehicle v ON v.id=d.vehicle_id
                JOIN bf_driver dr ON dr.id=d.driver_id
                WHERE o.order_type=?
                """ + (normalized == null ? " ORDER BY d.id DESC" : " AND d.status=? ORDER BY d.id DESC");
        Object[] args = normalized == null ? new Object[]{ORDER_TYPE} : new Object[]{ORDER_TYPE, normalized};
        return jdbcTemplate.query(sql, (rs, n) -> new VehicleRentalDispatchResponse(
                rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getString(6), rs.getString(7),
                rs.getLong(8), rs.getString(9), rs.getString(10),
                rs.getObject(11, LocalDateTime.class), rs.getObject(12, LocalDateTime.class),
                rs.getObject(13, LocalDateTime.class)), args);
    }

    private VehicleRentalDispatchResponse transition(AuthenticatedPrincipal principal, long dispatchId, String expected, String target, String reason) {
        long operator = ensureUser(principal);
        DispatchLocked dispatch = lockDispatch(dispatchId);
        if (!expected.equals(dispatch.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "派车单当前状态不允许该操作：" + dispatch.status());
        String timestamp = "ACCEPTED".equals(target) ? "accepted_at=CURRENT_TIMESTAMP," : "departed_at=CURRENT_TIMESTAMP,";
        jdbcTemplate.update("UPDATE bf_vehicle_dispatch SET status='" + target + "'," + timestamp + "updated_at=CURRENT_TIMESTAMP WHERE id=?", dispatchId);
        if ("IN_SERVICE".equals(target)) {
            jdbcTemplate.update("""
                    INSERT INTO bf_vehicle_handover(dispatch_id,delivery_at,delivery_mileage_km,delivery_condition,delivery_photos,status)
                    VALUES (?,CURRENT_TIMESTAMP,?,'交车并开始用车',JSON_ARRAY(),'DELIVERED')
                    ON DUPLICATE KEY UPDATE delivery_at=CURRENT_TIMESTAMP,delivery_mileage_km=VALUES(delivery_mileage_km),status='DELIVERED'
                    """, dispatchId, dispatch.vehicleMileageKm());
        }
        changeOrderStatus(dispatch.orderId(), expected, target, operator, reason);
        tripLog(dispatchId, target, null, null, reason);
        audit(operator, "VEHICLE_RENTAL_" + target, dispatchId, reason);
        return find(dispatchId);
    }

    private RentalLocked lockRental(long orderId) { List<RentalLocked> rows=jdbcTemplate.query("""
            SELECT o.status,i.id item_id,m.id model_id,b.start_at,b.end_at,b.passenger_count FROM bf_order o JOIN bf_order_item i ON i.order_id=o.id
            JOIN bf_vehicle_booking b ON b.order_item_id=i.id JOIN bf_vehicle_model m ON i.sku_code=CONCAT('VEHICLE_RENTAL_',m.model_code)
            WHERE o.id=? AND o.order_type=? FOR UPDATE
            """,(rs,n)->new RentalLocked(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getObject(4,LocalDateTime.class),rs.getObject(5,LocalDateTime.class),rs.getInt(6)),orderId,ORDER_TYPE);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"社会租赁订单不存在");return rows.get(0); }
    private VehicleLocked lockVehicle(long id){List<VehicleLocked> rows=jdbcTemplate.query("SELECT v.id,v.model_id,v.status,m.seat_count FROM bf_vehicle v JOIN bf_vehicle_model m ON m.id=v.model_id WHERE v.id=? FOR UPDATE",(rs,n)->new VehicleLocked(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getInt(4)),id);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"车辆不存在");return rows.get(0);}
    private DriverLocked lockDriver(long id){List<DriverLocked> rows=jdbcTemplate.query("SELECT id,status FROM bf_driver WHERE id=? FOR UPDATE",(rs,n)->new DriverLocked(rs.getLong(1),rs.getString(2)),id);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"司机不存在");return rows.get(0);}
    private DispatchLocked lockDispatch(long id){List<DispatchLocked> rows=jdbcTemplate.query("SELECT d.status,d.vehicle_id,d.driver_id,i.order_id,v.mileage_km FROM bf_vehicle_dispatch d JOIN bf_order_item i ON i.id=d.order_item_id JOIN bf_order o ON o.id=i.order_id JOIN bf_vehicle v ON v.id=d.vehicle_id WHERE d.id=? AND o.order_type=? FOR UPDATE",(rs,n)->new DispatchLocked(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getBigDecimal(5)),id,ORDER_TYPE);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"社会租赁派车单不存在");return rows.get(0);}
    private long insertDispatch(long item,long vehicle,long driver){KeyHolder h=new GeneratedKeyHolder();jdbcTemplate.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO bf_vehicle_dispatch(order_item_id,vehicle_id,driver_id,status) VALUES (?,?,?,'WAITING_DRIVER')",Statement.RETURN_GENERATED_KEYS);s.setLong(1,item);s.setLong(2,vehicle);s.setLong(3,driver);return s;},h);if(h.getKey()==null)throw new BusinessException(CommonErrorCode.INTERNAL_ERROR,"派车单创建失败");return h.getKey().longValue();}
    private void changeOrderStatus(long id,String from,String to,long operator,String reason){if(jdbcTemplate.update("UPDATE bf_order SET status=? WHERE id=? AND status=?",to,id,from)!=1)throw new BusinessException(CommonErrorCode.CONFLICT,"订单状态已变化，请刷新后重试");jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,?,?,?,?)",id,from,to,operator,reason,"VEHICLE-RENTAL-STATUS:"+id+":"+to);}
    private void tripLog(long id,String type,BigDecimal mileage,BigDecimal fuel,String detail){jdbcTemplate.update("INSERT INTO bf_vehicle_trip_log(dispatch_id,event_type,event_at,mileage_km,fuel_liter,detail) VALUES (?,?,CURRENT_TIMESTAMP,?,?,JSON_OBJECT('remark',?))",id,type,mileage,fuel,detail);}
    private void audit(long operator,String action,long dispatch,String detail){jdbcTemplate.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?, 'VEHICLE_DISPATCH', ?, JSON_OBJECT('detail',?))",operator,action,String.valueOf(dispatch),detail);}
    private long ensureUser(AuthenticatedPrincipal p){jdbcTemplate.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return jdbcTemplate.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());}
    private VehicleRentalDispatchResponse find(long id){List<VehicleRentalDispatchResponse> rows=jdbcTemplate.query("""
            SELECT d.id,o.id,o.order_no,o.status,v.id,v.vehicle_no,v.plate_no,dr.id,dr.driver_no,d.status,d.accepted_at,d.departed_at,d.completed_at
            FROM bf_vehicle_dispatch d JOIN bf_order_item i ON i.id=d.order_item_id JOIN bf_order o ON o.id=i.order_id JOIN bf_vehicle v ON v.id=d.vehicle_id JOIN bf_driver dr ON dr.id=d.driver_id
            WHERE d.id=? AND o.order_type=?
            """,(rs,n)->new VehicleRentalDispatchResponse(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getLong(8),rs.getString(9),rs.getString(10),rs.getObject(11,LocalDateTime.class),rs.getObject(12,LocalDateTime.class),rs.getObject(13,LocalDateTime.class)),id,ORDER_TYPE);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"社会租赁派车单不存在");return rows.get(0);}
    private record RentalLocked(String orderStatus,long itemId,long modelId,LocalDateTime startAt,LocalDateTime endAt,int passengerCount){}
    private record VehicleLocked(long id,long modelId,String status,int seatCount){}
    private record DriverLocked(long id,String status){}
    private record DispatchLocked(String status,long vehicleId,long driverId,long orderId,BigDecimal vehicleMileageKm){}
}
