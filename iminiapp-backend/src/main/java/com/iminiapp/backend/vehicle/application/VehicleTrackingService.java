package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.vehicle.api.VehicleLocationUpdateRequest;
import com.iminiapp.backend.vehicle.api.VehicleTripLogResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class VehicleTrackingService {
    private final JdbcTemplate jdbc;
    public VehicleTrackingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public VehicleTripLogResponse update(AuthenticatedPrincipal principal, long dispatchId, VehicleLocationUpdateRequest request) {
        validate(request);
        long operator = ensureUser(principal);
        List<String> statuses = jdbc.queryForList("SELECT status FROM bf_vehicle_dispatch WHERE id=? FOR UPDATE", String.class, dispatchId);
        if (statuses.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "派车单不存在");
        if (!List.of("ACCEPTED", "IN_SERVICE").contains(statuses.get(0))) throw new BusinessException(CommonErrorCode.CONFLICT, "只有已接单或行程中的派车单可以上报位置");
        LocalDateTime eventAt = request.eventAt() == null ? LocalDateTime.now() : request.eventAt();
        String coordinateSystem = normalizeCoordinateSystem(request.coordinateSystem());
        jdbc.update("UPDATE bf_vehicle_dispatch SET current_longitude=?,current_latitude=?,coordinate_system=?,location_updated_at=? WHERE id=?",
                request.longitude(), request.latitude(), coordinateSystem, eventAt, dispatchId);
        jdbc.update("INSERT INTO bf_vehicle_trip_log(dispatch_id,event_type,event_at,longitude,latitude,mileage_km,detail) VALUES (?,'POSITION',?,?,?,?,JSON_OBJECT('remark',?))",
                dispatchId, eventAt, request.longitude(), request.latitude(), request.mileageKm(), request.remark());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,'VEHICLE_POSITION_UPDATE','VEHICLE_DISPATCH',?,JSON_OBJECT('longitude',?,'latitude',?,'coordinateSystem',?))",
                operator, String.valueOf(dispatchId), request.longitude(), request.latitude(), coordinateSystem);
        return find(id == null ? 0 : id);
    }

    @Transactional(readOnly = true)
    public List<VehicleTripLogResponse> logs(long dispatchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bf_vehicle_dispatch WHERE id=?", Integer.class, dispatchId);
        if (count == null || count == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "派车单不存在");
        return jdbc.query(selectSql() + " WHERE dispatch_id=? ORDER BY event_at,id", (rs,n)->map(rs), dispatchId);
    }

    private VehicleTripLogResponse find(long id){return jdbc.queryForObject(selectSql()+" WHERE id=?",(rs,n)->map(rs),id);}
    private String selectSql(){return "SELECT id,dispatch_id,event_type,event_at,longitude,latitude,mileage_km,fuel_liter,CAST(detail AS CHAR) detail FROM bf_vehicle_trip_log";}
    private VehicleTripLogResponse map(java.sql.ResultSet rs)throws java.sql.SQLException{return new VehicleTripLogResponse(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getObject(4,LocalDateTime.class),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getString(9));}
    private void validate(VehicleLocationUpdateRequest r){if(r==null||r.longitude()==null||r.latitude()==null)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"经纬度不能为空");if(r.longitude().compareTo(BigDecimal.valueOf(-180))<0||r.longitude().compareTo(BigDecimal.valueOf(180))>0||r.latitude().compareTo(BigDecimal.valueOf(-90))<0||r.latitude().compareTo(BigDecimal.valueOf(90))>0)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"经纬度超出范围");if(r.mileageKm()!=null&&r.mileageKm().compareTo(BigDecimal.ZERO)<0)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"里程不能为负数");if(r.eventAt()!=null&&(r.eventAt().isAfter(LocalDateTime.now().plusMinutes(1))||r.eventAt().isBefore(LocalDateTime.now().minusMinutes(10))))throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"位置时间超出允许范围");if(r.remark()!=null&&r.remark().trim().length()>255)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"位置备注最长255字符");}
    private String normalizeCoordinateSystem(String value){if(value==null||value.isBlank())return "GCJ-02";String normalized=value.trim().toUpperCase(Locale.ROOT);if(!"GCJ-02".equals(normalized))throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"车辆位置仅支持 GCJ-02 坐标系");return normalized;}
    private long ensureUser(AuthenticatedPrincipal p){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());}
}
