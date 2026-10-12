package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.vehicle.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DriverVehicleService {
    private final JdbcTemplate jdbc;
    private final VehicleRentalDispatchService dispatchService;
    private final VehicleTrackingService trackingService;
    private final VehicleApplicationService applicationService;
    private final VehicleRentalSettlementService settlementService;

    public DriverVehicleService(JdbcTemplate jdbc, VehicleRentalDispatchService dispatchService,
                                VehicleTrackingService trackingService,
                                VehicleApplicationService applicationService,
                                VehicleRentalSettlementService settlementService) {
        this.jdbc = jdbc;
        this.dispatchService = dispatchService;
        this.trackingService = trackingService;
        this.applicationService = applicationService;
        this.settlementService = settlementService;
    }

    @Transactional(readOnly = true)
    public DriverProfileResponse profile(AuthenticatedPrincipal principal) {
        Long userId = existingUser(principal);
        if (userId == null) return DriverProfileResponse.notBound();
        return jdbc.query("SELECT id,driver_no,status FROM bf_driver WHERE user_id=?",
                (rs, rowNum) -> new DriverProfileResponse(true, rs.getLong("id"), rs.getString("driver_no"), rs.getString("status")), userId)
                .stream().findFirst().orElseGet(DriverProfileResponse::notBound);
    }

    @Transactional(readOnly = true)
    public List<DriverTaskResponse> tasks(AuthenticatedPrincipal principal, String status) {
        long driverId = requireDriver(principal);
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (normalized != null && !Set.of("WAITING_DRIVER", "ACCEPTED", "IN_SERVICE", "COMPLETED").contains(normalized)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "司机任务状态不正确");
        }
        String sql = selectSql() + " WHERE d.driver_id=?" + (normalized == null ? "" : " AND d.status=?")
                + " ORDER BY CASE d.status WHEN 'IN_SERVICE' THEN 1 WHEN 'ACCEPTED' THEN 2 WHEN 'WAITING_DRIVER' THEN 3 ELSE 4 END,d.id DESC LIMIT 100";
        return normalized == null ? jdbc.query(sql, this::map, driverId) : jdbc.query(sql, this::map, driverId, normalized);
    }

    @Transactional(readOnly = true)
    public DriverTaskResponse task(AuthenticatedPrincipal principal, long dispatchId) {
        long driverId = requireDriver(principal);
        List<DriverTaskResponse> rows = jdbc.query(selectSql() + " WHERE d.id=? AND d.driver_id=?",
                this::map, dispatchId, driverId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "司机任务不存在");
        return rows.get(0);
    }

    @Transactional
    public DriverTaskResponse accept(AuthenticatedPrincipal principal, long dispatchId) {
        TaskRef task = requireOwnedTask(principal, dispatchId);
        if ("VEHICLE_INTERNAL".equals(task.orderType())) applicationService.accept(principal, dispatchId);
        else dispatchService.accept(principal, dispatchId);
        return task(principal, dispatchId);
    }

    @Transactional
    public DriverTaskResponse depart(AuthenticatedPrincipal principal, long dispatchId) {
        TaskRef task = requireOwnedTask(principal, dispatchId);
        if ("VEHICLE_INTERNAL".equals(task.orderType())) applicationService.depart(principal, dispatchId);
        else dispatchService.depart(principal, dispatchId);
        return task(principal, dispatchId);
    }

    @Transactional
    public DriverTaskResponse complete(AuthenticatedPrincipal principal, long dispatchId,
                                       VehicleTripCompleteRequest request) {
        TaskRef task = requireOwnedTask(principal, dispatchId);
        if ("VEHICLE_INTERNAL".equals(task.orderType())) {
            applicationService.complete(principal, dispatchId, request);
        } else {
            settlementService.returnVehicle(principal, dispatchId, new VehicleReturnRequest(
                    null, request.endMileageKm(), request.fuelLiter(), request.remark(), List.of()));
        }
        return task(principal, dispatchId);
    }

    @Transactional
    public VehicleTripLogResponse updateLocation(AuthenticatedPrincipal principal, long dispatchId,
                                                 VehicleLocationUpdateRequest request) {
        requireOwnedTask(principal, dispatchId);
        return trackingService.update(principal, dispatchId, request);
    }

    private TaskRef requireOwnedTask(AuthenticatedPrincipal principal, long dispatchId) {
        long driverId = requireDriver(principal);
        List<TaskRef> rows = jdbc.query("""
                SELECT o.order_type FROM bf_vehicle_dispatch d
                JOIN bf_order_item i ON i.id=d.order_item_id JOIN bf_order o ON o.id=i.order_id
                WHERE d.id=? AND d.driver_id=?
                """, (rs, n) -> new TaskRef(rs.getString("order_type")), dispatchId, driverId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "司机任务不存在");
        TaskRef task = rows.get(0);
        if (!Set.of("VEHICLE_INTERNAL", "VEHICLE_RENTAL").contains(task.orderType())) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "该派车单不属于车辆业务");
        }
        return task;
    }

    private long requireDriver(AuthenticatedPrincipal principal) {
        DriverProfileResponse profile = profile(principal);
        if (!profile.driver() || profile.driverId() == null) throw new BusinessException(CommonErrorCode.FORBIDDEN, "当前账号未绑定司机档案");
        if ("INACTIVE".equals(profile.status())) throw new BusinessException(CommonErrorCode.FORBIDDEN, "司机账号已停用");
        return profile.driverId();
    }

    private Long existingUser(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, principal.databaseOpenId());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String selectSql() { return """
            SELECT d.id dispatch_id,o.id order_id,o.order_no,b.business_mode,o.status order_status,d.status dispatch_status,
                   v.id vehicle_id,v.vehicle_no,v.plate_no,b.departure,b.departure_longitude,b.departure_latitude,
                   b.destination,b.destination_longitude,b.destination_latitude,b.coordinate_system,b.start_at,b.end_at,
                   b.passenger_count,d.accepted_at,d.departed_at,d.completed_at
            FROM bf_vehicle_dispatch d JOIN bf_order_item i ON i.id=d.order_item_id
            JOIN bf_order o ON o.id=i.order_id JOIN bf_vehicle_booking b ON b.order_item_id=i.id
            JOIN bf_vehicle v ON v.id=d.vehicle_id
            """; }

    private DriverTaskResponse map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DriverTaskResponse(rs.getLong("dispatch_id"), rs.getLong("order_id"), rs.getString("order_no"),
                rs.getString("business_mode"), rs.getString("order_status"), rs.getString("dispatch_status"), rs.getLong("vehicle_id"),
                rs.getString("vehicle_no"), rs.getString("plate_no"), rs.getString("departure"),
                rs.getBigDecimal("departure_longitude"), rs.getBigDecimal("departure_latitude"),
                rs.getString("destination"), rs.getBigDecimal("destination_longitude"), rs.getBigDecimal("destination_latitude"),
                rs.getString("coordinate_system"), rs.getObject("start_at", java.time.LocalDateTime.class),
                rs.getObject("end_at", java.time.LocalDateTime.class), rs.getInt("passenger_count"),
                rs.getObject("accepted_at", java.time.LocalDateTime.class), rs.getObject("departed_at", java.time.LocalDateTime.class),
                rs.getObject("completed_at", java.time.LocalDateTime.class));
    }
    private record TaskRef(String orderType) {}
}
