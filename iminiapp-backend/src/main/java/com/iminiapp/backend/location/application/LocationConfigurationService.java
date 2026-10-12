package com.iminiapp.backend.location.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.location.api.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class LocationConfigurationService {
    private static final Set<String> TYPES = Set.of("VENUE", "COMMON_PICKUP");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocationConfigurationService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<LocationPlaceResponse> publicPlaces(String type) {
        String normalized = optionalType(type);
        String sql = selectSql() + " WHERE status='ACTIVE'" + (normalized == null ? "" : " AND place_type=?") + " ORDER BY sort_order,id";
        return normalized == null ? jdbc.query(sql, this::map) : jdbc.query(sql, this::map, normalized);
    }

    @Transactional(readOnly = true)
    public List<LocationPlaceResponse> allPlaces() {
        return jdbc.query(selectSql() + " ORDER BY place_type,sort_order,id", this::map);
    }

    @Transactional
    public LocationPlaceResponse create(AuthenticatedPrincipal principal, LocationPlaceRequest request) {
        Values value = values(request);
        long operator = ensureUser(principal);
        jdbc.update("""
                INSERT INTO bf_location_place(place_code,place_type,name,address,longitude,latitude,coordinate_system,sort_order,status,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, value.code(), value.type(), value.name(), value.address(), value.longitude(), value.latitude(),
                value.coordinateSystem(), value.sortOrder(), value.status(), operator);
        Long id = jdbc.queryForObject("SELECT id FROM bf_location_place WHERE place_code=?", Long.class, value.code());
        audit(operator, "LOCATION_PLACE_CREATE", id == null ? 0 : id);
        return find(id == null ? 0 : id);
    }

    @Transactional
    public LocationPlaceResponse update(AuthenticatedPrincipal principal, long id, LocationPlaceRequest request) {
        Values value = values(request);
        long operator = ensureUser(principal);
        int changed = jdbc.update("""
                UPDATE bf_location_place SET place_code=?,place_type=?,name=?,address=?,longitude=?,latitude=?,
                    coordinate_system=?,sort_order=?,status=?,updated_by=? WHERE id=?
                """, value.code(), value.type(), value.name(), value.address(), value.longitude(), value.latitude(),
                value.coordinateSystem(), value.sortOrder(), value.status(), operator, id);
        if (changed != 1) throw new BusinessException(CommonErrorCode.NOT_FOUND, "地图地点不存在");
        audit(operator, "LOCATION_PLACE_UPDATE", id);
        return find(id);
    }

    @Transactional(readOnly = true)
    public DiningDeliveryScopeResponse deliveryScope(long storeId) {
        return jdbc.query("""
                SELECT id,store_code,name,delivery_scope_type,CAST(delivery_scope_config AS CHAR) scope_config
                FROM bf_dining_store WHERE id=?
                """, (rs, rowNum) -> scope(rs.getLong("id"), rs.getString("store_code"), rs.getString("name"),
                rs.getString("delivery_scope_type"), rs.getString("scope_config")), storeId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "餐饮门店不存在"));
    }

    @Transactional
    public DiningDeliveryScopeResponse updateDeliveryScope(AuthenticatedPrincipal principal, long storeId,
                                                            DiningDeliveryScopeRequest request) {
        if (request == null || request.areas() == null) throw invalid("内部配送区域不能为空");
        List<String> areas = request.areas().stream().map(value -> text(value, 120, "配送区域"))
                .distinct().limit(50).toList();
        if (areas.isEmpty()) throw invalid("至少配置一个内部配送区域");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("areas", areas);
        config.put("roomRequired", !Boolean.FALSE.equals(request.roomRequired()));
        String json;
        try { json = objectMapper.writeValueAsString(config); }
        catch (JsonProcessingException ex) { throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "配送范围配置序列化失败"); }
        long operator = ensureUser(principal);
        if (jdbc.update("UPDATE bf_dining_store SET delivery_scope_type='INTERNAL_AREA',delivery_scope_config=CAST(? AS JSON),updated_by=? WHERE id=?",
                json, operator, storeId) != 1) throw new BusinessException(CommonErrorCode.NOT_FOUND, "餐饮门店不存在");
        audit(operator, "DINING_DELIVERY_SCOPE_UPDATE", storeId);
        return deliveryScope(storeId);
    }

    private DiningDeliveryScopeResponse scope(long id, String code, String name, String type, String json) {
        List<String> areas = new ArrayList<>();
        boolean roomRequired = true;
        if (StringUtils.hasText(json)) {
            try {
                JsonNode root = objectMapper.readTree(json);
                if (root.path("areas").isArray()) root.path("areas").forEach(node -> areas.add(node.asText()));
                roomRequired = root.path("roomRequired").asBoolean(true);
            } catch (JsonProcessingException ignored) { }
        }
        return new DiningDeliveryScopeResponse(id, code, name, type, List.copyOf(areas), roomRequired);
    }

    private Values values(LocationPlaceRequest request) {
        if (request == null) throw invalid("地图地点资料不能为空");
        String code = text(request.placeCode(), 64, "地点编码").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_]+")) throw invalid("地点编码仅支持大写字母、数字和下划线");
        String type = text(request.placeType(), 32, "地点类型").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw invalid("地点类型仅支持 VENUE 或 COMMON_PICKUP");
        BigDecimal longitude = request.longitude(), latitude = request.latitude();
        if ((longitude == null) != (latitude == null)) throw invalid("经纬度必须同时提供");
        if (longitude != null && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) throw invalid("经纬度超出范围");
        String coordinate = StringUtils.hasText(request.coordinateSystem()) ? request.coordinateSystem().trim().toUpperCase(Locale.ROOT) : "GCJ-02";
        if (!"GCJ-02".equals(coordinate)) throw invalid("地图坐标系仅支持 GCJ-02");
        String status = StringUtils.hasText(request.status()) ? request.status().trim().toUpperCase(Locale.ROOT) : "INACTIVE";
        if (!STATUSES.contains(status)) throw invalid("地点状态不正确");
        if ("ACTIVE".equals(status) && longitude == null) throw invalid("启用导航前必须配置经纬度");
        int sort = request.sortOrder() == null ? 0 : request.sortOrder();
        if (sort < 0 || sort > 100000) throw invalid("排序值应为0至100000");
        return new Values(code, type, text(request.name(), 120, "地点名称"), optional(request.address(), 255),
                longitude, latitude, coordinate, sort, status);
    }

    private String optionalType(String value) { if (!StringUtils.hasText(value)) return null; String type=value.trim().toUpperCase(Locale.ROOT); if(!TYPES.contains(type))throw invalid("地点类型不正确");return type; }
    private LocationPlaceResponse find(long id) { return jdbc.query(selectSql()+" WHERE id=?",this::map,id).stream().findFirst().orElseThrow(()->new BusinessException(CommonErrorCode.NOT_FOUND,"地图地点不存在")); }
    private String selectSql(){return "SELECT id,place_code,place_type,name,address,longitude,latitude,coordinate_system,sort_order,status,updated_at FROM bf_location_place";}
    private LocationPlaceResponse map(java.sql.ResultSet rs,int row)throws java.sql.SQLException{BigDecimal longitude=rs.getBigDecimal("longitude"),latitude=rs.getBigDecimal("latitude");return new LocationPlaceResponse(rs.getLong("id"),rs.getString("place_code"),rs.getString("place_type"),rs.getString("name"),rs.getString("address"),longitude,latitude,rs.getString("coordinate_system"),rs.getInt("sort_order"),rs.getString("status"),longitude!=null&&latitude!=null&&"ACTIVE".equals(rs.getString("status")),rs.getObject("updated_at", LocalDateTime.class));}
    private long ensureUser(AuthenticatedPrincipal principal){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",principal.databaseOpenId(),principal.displayName());return jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,principal.databaseOpenId());}
    private void audit(long operator,String action,long id){jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,'LOCATION_CONFIG',?,JSON_OBJECT())",operator,action,String.valueOf(id));}
    private String text(String value,int max,String label){if(!StringUtils.hasText(value)||value.trim().length()>max)throw invalid(label+"不能为空且最长"+max+"字符");return value.trim();}
    private String optional(String value,int max){if(!StringUtils.hasText(value))return null;if(value.trim().length()>max)throw invalid("字段最长"+max+"字符");return value.trim();}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private record Values(String code,String type,String name,String address,BigDecimal longitude,BigDecimal latitude,String coordinateSystem,int sortOrder,String status){}
}
