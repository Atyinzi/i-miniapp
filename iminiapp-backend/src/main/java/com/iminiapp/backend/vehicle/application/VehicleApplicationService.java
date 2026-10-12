package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.vehicle.api.*;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 内部用车申请闭环。社会租赁的报价、押金和支付将在第二阶段接入。 */
@Service
public class VehicleApplicationService {
    private static final String ORDER_TYPE = "VEHICLE_INTERNAL";
    private static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    private static final String APPROVED = "APPROVED";
    private final JdbcTemplate jdbcTemplate;
    private final VehicleEmployeeService employeeService;

    public VehicleApplicationService(JdbcTemplate jdbcTemplate, VehicleEmployeeService employeeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.employeeService = employeeService;
    }

    @Transactional
    public VehicleApplicationResponse create(AuthenticatedPrincipal principal, String idempotencyKey, VehicleApplicationCreateRequest request) {
        VehicleRequest values = validateCreate(idempotencyKey, request);
        long userId = employeeService.requireActiveEmployee(principal);
        List<Long> existing = jdbcTemplate.queryForList("SELECT id FROM bf_order WHERE user_id=? AND client_request_id=?", Long.class, userId, idempotencyKey.trim());
        if (!existing.isEmpty()) return findApplication(existing.get(0), userId, true);
        long skuId = internalApplicationSkuId();
        String orderNo = "BFHVC" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()) + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        long orderId = insertOrder(userId, idempotencyKey.trim(), orderNo, values.purpose());
        long itemId = insertOrderItem(orderId, skuId);
        jdbcTemplate.update("""
                INSERT INTO bf_vehicle_booking(order_item_id,business_mode,start_at,end_at,departure,destination,passenger_count,estimated_km,rental_mode,dispatch_deadline,deposit_amount_cent)
                VALUES (?,'INTERNAL',?,?,?,?,?,?,'INTERNAL',?,0)
                """, itemId, values.startAt(), values.endAt(), values.departure(), values.destination(), values.passengerCount(), values.estimatedKm(), values.startAt().minusHours(1));
        jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,?,?,?,?)",
                orderId, null, PENDING_APPROVAL, userId, values.purpose(), "VEHICLE-CREATE:" + orderId);
        audit(userId, "VEHICLE_APPLICATION_CREATE", "VEHICLE_APPLICATION", itemId, values.purpose());
        return findApplication(orderId, userId, true);
    }

    @Transactional(readOnly = true)
    public List<VehicleApplicationResponse> listMine(AuthenticatedPrincipal principal) {
        long userId = employeeService.requireBoundEmployee(principal);
        return jdbcTemplate.query("SELECT id FROM bf_order WHERE user_id=? AND order_type=? ORDER BY id DESC", (rs, n) -> rs.getLong(1), userId, ORDER_TYPE)
                .stream().map(id -> findApplication(id, userId, true)).toList();
    }

    @Transactional(readOnly = true)
    public VehicleApplicationResponse detailMine(AuthenticatedPrincipal principal, long applicationId) {
        return findApplication(applicationId, employeeService.requireBoundEmployee(principal), true);
    }

    @Transactional
    public VehicleApplicationResponse cancelMine(AuthenticatedPrincipal principal, long applicationId) {
        long userId = employeeService.requireBoundEmployee(principal);
        ApplicationLocked application = lockApplication(applicationId);
        Integer owned = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_order WHERE id=? AND user_id=?",
                Integer.class, applicationId, userId);
        if (owned == null || owned == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "内部用车申请不存在");
        if (!PENDING_APPROVAL.equals(application.status())) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "只有待审核的内部用车申请可以取消");
        }
        changeOrderStatus(applicationId, PENDING_APPROVAL, "CANCELLED", userId, "员工主动取消内部用车申请");
        audit(userId, "VEHICLE_APPLICATION_CANCEL", "VEHICLE_APPLICATION", application.itemId(), "员工主动取消");
        return findApplication(applicationId, userId, true);
    }

    @Transactional
    public List<VehicleResponse> listVehicles(AuthenticatedPrincipal principal, String status, Long modelId) {
        ensureUser(principal);
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (normalized != null && !List.of("AVAILABLE", "DISPATCHED", "MAINTENANCE", "INACTIVE").contains(normalized)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "车辆状态不正确");
        }
        if (modelId != null && modelId < 1) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "modelId 不正确");
        StringBuilder sql = new StringBuilder("SELECT v.id,v.vehicle_no,v.plate_no,m.model_code,m.name,m.seat_count,v.status,v.mileage_km FROM bf_vehicle v JOIN bf_vehicle_model m ON m.id=v.model_id");
        List<Object> arguments = new java.util.ArrayList<>();
        if (normalized != null || modelId != null) {
            sql.append(" WHERE ");
            if (normalized != null) { sql.append("v.status=?"); arguments.add(normalized); }
            if (normalized != null && modelId != null) sql.append(" AND ");
            if (modelId != null) { sql.append("v.model_id=?"); arguments.add(modelId); }
        }
        sql.append(" ORDER BY v.id");
        return jdbcTemplate.query(sql.toString(), (rs, n) -> vehicle(rs), arguments.toArray());
    }

    @Transactional
    public List<VehicleDriverResponse> listDrivers(AuthenticatedPrincipal principal, String status) {
        ensureUser(principal);
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (normalized != null && !List.of("AVAILABLE", "BUSY", "INACTIVE").contains(normalized)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "司机状态不正确");
        }
        String sql = "SELECT id,driver_no,user_id,status FROM bf_driver" + (normalized == null ? " ORDER BY id" : " WHERE status=? ORDER BY id");
        return normalized == null ? jdbcTemplate.query(sql, (rs, n) -> new VehicleDriverResponse(rs.getLong(1), rs.getString(2), nullableLong(rs,"user_id"), rs.getString(4)))
                : jdbcTemplate.query(sql, (rs, n) -> new VehicleDriverResponse(rs.getLong(1), rs.getString(2), nullableLong(rs,"user_id"), rs.getString(4)), normalized);
    }

    @Transactional
    public List<VehicleApplicationResponse> listAll(AuthenticatedPrincipal principal, String status) {
        ensureUser(principal);
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (normalized != null && !List.of(PENDING_APPROVAL, APPROVED, "REJECTED", "CANCELLED", "WAITING_DRIVER", "ACCEPTED", "IN_SERVICE", "COMPLETED").contains(normalized)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "申请状态不正确");
        }
        String sql = "SELECT id FROM bf_order WHERE order_type=?" + (normalized == null ? " ORDER BY id DESC" : " AND status=? ORDER BY id DESC");
        List<Long> ids = normalized == null ? jdbcTemplate.query(sql, (rs, n) -> rs.getLong(1), ORDER_TYPE) : jdbcTemplate.query(sql, (rs, n) -> rs.getLong(1), ORDER_TYPE, normalized);
        return ids.stream().map(id -> findApplication(id, null, false)).toList();
    }

    @Transactional
    public VehicleApplicationResponse review(AuthenticatedPrincipal principal, long applicationId, VehicleApplicationReviewRequest request) {
        if (request == null || request.approved() == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "approved 不能为空");
        long operatorId = ensureUser(principal);
        ApplicationLocked application = lockApplication(applicationId);
        if (!PENDING_APPROVAL.equals(application.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "当前申请不处于待审批状态");
        String decision = request.approved() ? APPROVED : "REJECTED";
        String comment = optional(request.comment(), 500);
        jdbcTemplate.update("UPDATE bf_order SET status=? WHERE id=?", decision, applicationId);
        jdbcTemplate.update("INSERT INTO bf_vehicle_application_review(order_item_id,reviewer_id,decision,comment) VALUES (?,?,?,?)", application.itemId(), operatorId, decision, comment);
        jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,?,?,?,?)", applicationId, PENDING_APPROVAL, decision, operatorId, comment, "VEHICLE-REVIEW:" + applicationId);
        audit(operatorId, "VEHICLE_APPLICATION_" + decision, "VEHICLE_APPLICATION", application.itemId(), comment);
        return findApplication(applicationId, null, false);
    }

    @Transactional
    public VehicleApplicationResponse dispatch(AuthenticatedPrincipal principal, long applicationId, VehicleDispatchRequest request) {
        if (request == null || request.vehicleId() == null || request.driverId() == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "vehicleId 和 driverId 不能为空");
        long operatorId = ensureUser(principal);
        ApplicationLocked application = lockApplication(applicationId);
        if (!APPROVED.equals(application.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "只有已审批通过的申请可以派车");
        VehicleLocked vehicle = lockVehicle(request.vehicleId());
        if (!"AVAILABLE".equals(vehicle.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "车辆当前不可派遣");
        Integer unavailable = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_vehicle_unavailable_period WHERE vehicle_id=? AND status='ACTIVE' AND start_at<? AND end_at>?", Integer.class, vehicle.id(), application.endAt(), application.startAt());
        if (unavailable != null && unavailable > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "车辆在申请时段内维修、保养或被锁定");
        if (vehicle.seatCount() < application.passengerCount()) throw new BusinessException(CommonErrorCode.CONFLICT, "车辆座位数不足");
        DriverLocked driver = lockDriver(request.driverId());
        if (!"AVAILABLE".equals(driver.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "司机当前不可派遣");
        Integer conflicts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_vehicle_dispatch d JOIN bf_vehicle_booking b ON b.order_item_id=d.order_item_id
                JOIN bf_order o ON o.id=(SELECT order_id FROM bf_order_item WHERE id=d.order_item_id)
                WHERE d.vehicle_id=? AND d.status IN ('WAITING_DRIVER','ACCEPTED','IN_SERVICE')
                  AND NOT (b.end_at<=? OR b.start_at>=?)
                """, Integer.class, vehicle.id(), application.startAt(), application.endAt());
        if (conflicts != null && conflicts > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "该车辆在申请时段内已有派车任务");
        long dispatchId = insertDispatch(application.itemId(), vehicle.id(), driver.id());
        jdbcTemplate.update("UPDATE bf_vehicle SET status='DISPATCHED' WHERE id=?", vehicle.id());
        jdbcTemplate.update("UPDATE bf_driver SET status='BUSY' WHERE id=?", driver.id());
        changeOrderStatus(applicationId, APPROVED, "WAITING_DRIVER", operatorId, "已派车，等待司机接单");
        tripLog(dispatchId, "DISPATCHED", null, null, "管理员已派车");
        audit(operatorId, "VEHICLE_DISPATCH_CREATE", "VEHICLE_DISPATCH", dispatchId, "vehicleId=" + vehicle.id() + ",driverId=" + driver.id());
        return findApplication(applicationId, null, false);
    }

    @Transactional
    public VehicleApplicationResponse accept(AuthenticatedPrincipal principal, long dispatchId) { return changeDispatch(principal, dispatchId, "WAITING_DRIVER", "ACCEPTED", "司机接单"); }
    @Transactional
    public VehicleApplicationResponse depart(AuthenticatedPrincipal principal, long dispatchId) { return changeDispatch(principal, dispatchId, "ACCEPTED", "IN_SERVICE", "车辆出发"); }

    @Transactional
    public VehicleApplicationResponse complete(AuthenticatedPrincipal principal, long dispatchId, VehicleTripCompleteRequest request) {
        if (request == null || request.endMileageKm() == null || request.endMileageKm().compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "endMileageKm 必须为非负数");
        if (request.fuelLiter() != null && request.fuelLiter().compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "fuelLiter 不能为负数");
        long operatorId = ensureUser(principal);
        DispatchLocked dispatch = lockDispatch(dispatchId);
        if (!"IN_SERVICE".equals(dispatch.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "只有行程中的派车单可以完成");
        if (request.endMileageKm().compareTo(dispatch.vehicleMileageKm()) < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "结束里程不能小于当前车辆里程");
        jdbcTemplate.update("UPDATE bf_vehicle_dispatch SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=?", dispatchId);
        jdbcTemplate.update("UPDATE bf_vehicle SET status='AVAILABLE',mileage_km=? WHERE id=?", request.endMileageKm(), dispatch.vehicleId());
        jdbcTemplate.update("UPDATE bf_driver SET status='AVAILABLE' WHERE id=?", dispatch.driverId());
        changeOrderStatus(dispatch.orderId(), "IN_SERVICE", "COMPLETED", operatorId, optional(request.remark(), 500));
        tripLog(dispatchId, "COMPLETED", request.endMileageKm(), request.fuelLiter(), optional(request.remark(), 500));
        audit(operatorId, "VEHICLE_TRIP_COMPLETE", "VEHICLE_DISPATCH", dispatchId, optional(request.remark(), 500));
        return findApplication(dispatch.orderId(), null, false);
    }

    private VehicleApplicationResponse changeDispatch(AuthenticatedPrincipal principal, long dispatchId, String expected, String target, String remark) {
        long operatorId = ensureUser(principal);
        DispatchLocked dispatch = lockDispatch(dispatchId);
        if (!expected.equals(dispatch.status())) throw new BusinessException(CommonErrorCode.CONFLICT, "派车单当前状态不允许该操作：" + dispatch.status());
        String timestamp = "ACCEPTED".equals(target) ? "accepted_at=CURRENT_TIMESTAMP," : "departed_at=CURRENT_TIMESTAMP,";
        jdbcTemplate.update("UPDATE bf_vehicle_dispatch SET status='" + target + "'," + timestamp + " updated_at=CURRENT_TIMESTAMP WHERE id=?", dispatchId);
        changeOrderStatus(dispatch.orderId(), expected, target, operatorId, remark);
        tripLog(dispatchId, target, null, null, remark);
        audit(operatorId, "VEHICLE_DISPATCH_" + target, "VEHICLE_DISPATCH", dispatchId, remark);
        return findApplication(dispatch.orderId(), null, false);
    }

    private VehicleApplicationResponse findApplication(long orderId, Long ownerId, boolean requireOwner) {
        String sql = """
                SELECT o.id,o.order_no,o.status,o.created_at,b.departure,b.destination,b.passenger_count,b.estimated_km,b.start_at,b.end_at,
                       JSON_UNQUOTE(JSON_EXTRACT(o.remark,'$.purpose')) purpose,rv.comment,d.id dispatch_id,d.vehicle_id,v.vehicle_no,v.plate_no,d.driver_id,dr.driver_no,d.status dispatch_status
                FROM bf_order o JOIN bf_order_item i ON i.order_id=o.id JOIN bf_vehicle_booking b ON b.order_item_id=i.id
                LEFT JOIN bf_vehicle_application_review rv ON rv.order_item_id=i.id LEFT JOIN bf_vehicle_dispatch d ON d.order_item_id=i.id
                LEFT JOIN bf_vehicle v ON v.id=d.vehicle_id LEFT JOIN bf_driver dr ON dr.id=d.driver_id WHERE o.id=? AND o.order_type=?
                """;
        List<VehicleApplicationResponse> rows = jdbcTemplate.query(sql, (rs, n) -> new VehicleApplicationResponse(rs.getLong("id"), rs.getString("order_no"), rs.getString("status"), rs.getString("departure"), rs.getString("destination"), rs.getInt("passenger_count"), rs.getBigDecimal("estimated_km"), rs.getString("purpose"), rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class), rs.getString("comment"), nullableLong(rs, "dispatch_id"), nullableLong(rs, "vehicle_id"), rs.getString("vehicle_no"), rs.getString("plate_no"), nullableLong(rs, "driver_id"), rs.getString("driver_no"), rs.getString("dispatch_status"), rs.getObject("created_at", LocalDateTime.class)), orderId, ORDER_TYPE);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "内部用车申请不存在");
        if (requireOwner) {
            Integer owned = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_order WHERE id=? AND user_id=?", Integer.class, orderId, ownerId);
            if (owned == null || owned == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "内部用车申请不存在");
        }
        return rows.get(0);
    }

    private ApplicationLocked lockApplication(long orderId) {
        List<ApplicationLocked> rows = jdbcTemplate.query("""
                SELECT o.id,o.status,i.id item_id,b.start_at,b.end_at,b.passenger_count FROM bf_order o JOIN bf_order_item i ON i.order_id=o.id
                JOIN bf_vehicle_booking b ON b.order_item_id=i.id WHERE o.id=? AND o.order_type=? FOR UPDATE
                """, (rs,n) -> new ApplicationLocked(rs.getLong("id"),rs.getString("status"),rs.getLong("item_id"),rs.getObject("start_at",LocalDateTime.class),rs.getObject("end_at",LocalDateTime.class),rs.getInt("passenger_count")), orderId, ORDER_TYPE);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "内部用车申请不存在"); return rows.get(0);
    }
    private VehicleLocked lockVehicle(long id) { List<VehicleLocked> rows=jdbcTemplate.query("SELECT v.id,v.status,m.seat_count FROM bf_vehicle v JOIN bf_vehicle_model m ON m.id=v.model_id WHERE v.id=? FOR UPDATE",(rs,n)->new VehicleLocked(rs.getLong(1),rs.getString(2),rs.getInt(3)),id); if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"车辆不存在");return rows.get(0); }
    private DriverLocked lockDriver(long id) { List<DriverLocked> rows=jdbcTemplate.query("SELECT id,status FROM bf_driver WHERE id=? FOR UPDATE",(rs,n)->new DriverLocked(rs.getLong(1),rs.getString(2)),id);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"司机不存在");return rows.get(0); }
    private DispatchLocked lockDispatch(long id) { List<DispatchLocked> rows=jdbcTemplate.query("SELECT d.id,d.status,d.vehicle_id,d.driver_id,i.order_id,v.mileage_km FROM bf_vehicle_dispatch d JOIN bf_order_item i ON i.id=d.order_item_id JOIN bf_vehicle v ON v.id=d.vehicle_id WHERE d.id=? FOR UPDATE",(rs,n)->new DispatchLocked(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getBigDecimal(6)),id);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"派车单不存在");return rows.get(0); }
    private long insertOrder(long userId,String key,String no,String purpose){KeyHolder h=new GeneratedKeyHolder();jdbcTemplate.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO bf_order(order_no,user_id,client_request_id,order_type,status,total_amount_cent,discount_amount_cent,payable_amount_cent,paid_amount_cent,remark) VALUES (?,?,?,?,'PENDING_APPROVAL',0,0,0,0,JSON_OBJECT('purpose',?))",Statement.RETURN_GENERATED_KEYS);s.setString(1,no);s.setLong(2,userId);s.setString(3,key);s.setString(4,ORDER_TYPE);s.setString(5,purpose);return s;},h);return key(h,"申请创建失败");}
    private long insertOrderItem(long orderId,long skuId){KeyHolder h=new GeneratedKeyHolder();jdbcTemplate.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO bf_order_item(order_id,sku_id,sku_code,sku_name,resource_name,resource_type,quantity,unit_price_cent,amount_cent,snapshot) SELECT ?,s.id,s.sku_code,s.name,r.name,r.resource_type,1,0,0,JSON_OBJECT('businessMode','INTERNAL') FROM bf_resource_sku s JOIN bf_resource r ON r.id=s.resource_id WHERE s.id=?",Statement.RETURN_GENERATED_KEYS);s.setLong(1,orderId);s.setLong(2,skuId);return s;},h);return key(h,"申请明细创建失败");}
    private long insertDispatch(long item,long vehicle,long driver){KeyHolder h=new GeneratedKeyHolder();jdbcTemplate.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO bf_vehicle_dispatch(order_item_id,vehicle_id,driver_id,status) VALUES (?,?,?,'WAITING_DRIVER')",Statement.RETURN_GENERATED_KEYS);s.setLong(1,item);s.setLong(2,vehicle);s.setLong(3,driver);return s;},h);return key(h,"派车单创建失败");}
    private void changeOrderStatus(long orderId,String from,String to,long operator,String remark){int n=jdbcTemplate.update("UPDATE bf_order SET status=? WHERE id=? AND status=?",to,orderId,from);if(n!=1)throw new BusinessException(CommonErrorCode.CONFLICT,"申请状态已变化，请刷新后重试");jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,?,?,?,?)",orderId,from,to,operator,remark,"VEHICLE-STATUS:"+orderId+":"+to);}
    private void tripLog(long id,String type,BigDecimal mileage,BigDecimal fuel,String detail){jdbcTemplate.update("INSERT INTO bf_vehicle_trip_log(dispatch_id,event_type,event_at,mileage_km,fuel_liter,detail) VALUES (?,?,CURRENT_TIMESTAMP,?,?,JSON_OBJECT('remark',?))",id,type,mileage,fuel,detail);}
    private long internalApplicationSkuId(){List<Long> r=jdbcTemplate.queryForList("SELECT id FROM bf_resource_sku WHERE sku_code='VEHICLE_INTERNAL_APPLICATION' AND status='ACTIVE'",Long.class);if(r.isEmpty())throw new BusinessException(CommonErrorCode.INTERNAL_ERROR,"内部用车基础数据未初始化");return r.get(0);}
    private long ensureUser(AuthenticatedPrincipal p){jdbcTemplate.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return jdbcTemplate.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());}
    private long requireUser(AuthenticatedPrincipal p){List<Long> r=jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());if(r.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"用户不存在");return r.get(0);}
    private void audit(long operator,String action,String type,long target,String detail){jdbcTemplate.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,?,?,JSON_OBJECT('detail',?))",operator,action,type,String.valueOf(target),detail);}
    private VehicleRequest validateCreate(String key,VehicleApplicationCreateRequest r){if(!StringUtils.hasText(key)||key.trim().length()>128)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"Idempotency-Key 不能为空且最长128字符");if(r==null||r.startAt()==null||r.endAt()==null||!r.endAt().isAfter(r.startAt())||r.startAt().isBefore(LocalDateTime.now().minusMinutes(1)))throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"用车开始和结束时间不正确");if(r.passengerCount()==null||r.passengerCount()<1||r.passengerCount()>60)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"乘车人数应为1至60人");if(r.estimatedKm()!=null&&r.estimatedKm().compareTo(BigDecimal.ZERO)<0)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"预计里程不能为负数");return new VehicleRequest(r.startAt(),r.endAt(),required(r.departure(),"departure",255),required(r.destination(),"destination",255),r.passengerCount(),r.estimatedKm(),required(r.purpose(),"purpose",500));}
    private String required(String v,String field,int max){if(!StringUtils.hasText(v)||v.trim().length()>max)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,field+" 不能为空且最长"+max+"字符");return v.trim();}
    private String optional(String v,int max){if(!StringUtils.hasText(v))return null;if(v.trim().length()>max)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"字段最长"+max+"字符");return v.trim();}
    private long key(KeyHolder h,String m){if(h.getKey()==null)throw new BusinessException(CommonErrorCode.INTERNAL_ERROR,m);return h.getKey().longValue();}
    private Long nullableLong(java.sql.ResultSet rs,String c)throws java.sql.SQLException{long v=rs.getLong(c);return rs.wasNull()?null:v;}
    private VehicleResponse vehicle(java.sql.ResultSet rs)throws java.sql.SQLException{return new VehicleResponse(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getString(7),rs.getBigDecimal(8));}
    private record VehicleRequest(LocalDateTime startAt,LocalDateTime endAt,String departure,String destination,int passengerCount,BigDecimal estimatedKm,String purpose){}
    private record ApplicationLocked(long id,String status,long itemId,LocalDateTime startAt,LocalDateTime endAt,int passengerCount){}
    private record VehicleLocked(long id,String status,int seatCount){}
    private record DriverLocked(long id,String status){}
    private record DispatchLocked(long id,String status,long vehicleId,long driverId,long orderId,BigDecimal vehicleMileageKm){}
}
