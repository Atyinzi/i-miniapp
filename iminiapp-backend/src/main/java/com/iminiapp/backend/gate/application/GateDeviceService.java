package com.iminiapp.backend.gate.application;

import com.iminiapp.backend.gate.api.GateDeviceCreateRequest;
import com.iminiapp.backend.gate.api.GateDeviceResponse;
import com.iminiapp.backend.gate.api.GateDeviceUpdateRequest;
import com.iminiapp.backend.gate.api.GateEventResponse;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GateDeviceService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private final JdbcTemplate jdbc;
    private final GateSecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public GateDeviceService(JdbcTemplate jdbc, GateSecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<GateDeviceResponse> list() {
        return jdbc.query(selectSql() + " ORDER BY id", this::map);
    }

    @Transactional(readOnly = true)
    public List<GateEventResponse> events(Long deviceId, String decision, Integer requestedLimit) {
        String normalizedDecision = StringUtils.hasText(decision)
                ? decision.trim().toUpperCase(Locale.ROOT) : null;
        if (normalizedDecision != null && !Set.of("GRANTED", "DENIED").contains(normalizedDecision)) {
            throw invalid("闸机决策仅支持 GRANTED 或 DENIED");
        }
        int limit = requestedLimit == null ? 100 : requestedLimit;
        if (limit < 1 || limit > 500) throw invalid("查询条数应为1至500");
        StringBuilder sql = new StringBuilder("""
                SELECT e.id,e.device_id,d.device_no,d.name device_name,e.event_no,e.decision,e.message,
                       e.verification_ref,e.order_id,e.happened_at,e.created_at
                FROM bf_gate_event e JOIN bf_gate_device d ON d.id=e.device_id WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (deviceId != null) { sql.append(" AND e.device_id=?"); args.add(deviceId); }
        if (normalizedDecision != null) { sql.append(" AND e.decision=?"); args.add(normalizedDecision); }
        sql.append(" ORDER BY e.id DESC LIMIT ?"); args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> new GateEventResponse(
                rs.getLong("id"), rs.getLong("device_id"), rs.getString("device_no"),
                rs.getString("device_name"), rs.getString("event_no"), rs.getString("decision"),
                rs.getString("message"), rs.getObject("verification_ref", Long.class),
                rs.getObject("order_id", Long.class), rs.getObject("happened_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class)), args.toArray());
    }

    @Transactional
    public GateDeviceResponse create(AuthenticatedPrincipal principal, GateDeviceCreateRequest request) {
        if (request == null) throw invalid("闸机资料不能为空");
        String deviceNo = code(request.deviceNo(), 64, "设备编号");
        String name = text(request.name(), 120, "设备名称");
        String venueCode = optionalCode(request.venueCode(), 64, "场馆编码");
        String secret = newSecret();
        long operator = ensureUser(principal);
        try {
            jdbc.update("""
                    INSERT INTO bf_gate_device
                    (device_no,name,venue_code,integration_mode,secret_cipher,status,created_by,updated_by)
                    VALUES (?,?,?,'API_HMAC',?,'INACTIVE',?,?)
                    """, deviceNo, name, venueCode, cipher.encrypt(secret), operator, operator);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "闸机设备编号已存在");
        }
        Long id = jdbc.queryForObject("SELECT id FROM bf_gate_device WHERE device_no=?", Long.class, deviceNo);
        if (id == null) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "闸机设备创建失败");
        audit(operator, "GATE_DEVICE_CREATE", id);
        GateDeviceResponse saved = find(id);
        return withSecret(saved, secret);
    }

    @Transactional
    public GateDeviceResponse update(AuthenticatedPrincipal principal, long id, GateDeviceUpdateRequest request) {
        if (request == null) throw invalid("闸机资料不能为空");
        String name = text(request.name(), 120, "设备名称");
        String venueCode = optionalCode(request.venueCode(), 64, "场馆编码");
        String status = text(request.status(), 20, "设备状态").toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw invalid("设备状态仅支持 ACTIVE 或 INACTIVE");
        if ("ACTIVE".equals(status) && venueCode == null) throw invalid("启用闸机前必须绑定业务场馆编码");
        long operator = ensureUser(principal);
        if (jdbc.update("UPDATE bf_gate_device SET name=?,venue_code=?,status=?,updated_by=? WHERE id=?",
                name, venueCode, status, operator, id) != 1) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "闸机设备不存在");
        }
        audit(operator, "GATE_DEVICE_UPDATE", id);
        return find(id);
    }

    @Transactional
    public GateDeviceResponse rotateSecret(AuthenticatedPrincipal principal, long id) {
        String secret = newSecret();
        long operator = ensureUser(principal);
        if (jdbc.update("UPDATE bf_gate_device SET secret_cipher=?,status='INACTIVE',updated_by=? WHERE id=?",
                cipher.encrypt(secret), operator, id) != 1) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "闸机设备不存在");
        }
        audit(operator, "GATE_DEVICE_SECRET_ROTATE", id);
        return withSecret(find(id), secret);
    }

    private GateDeviceResponse find(long id) {
        return jdbc.query(selectSql() + " WHERE id=?", this::map, id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "闸机设备不存在"));
    }

    private String selectSql() {
        return "SELECT id,device_no,name,venue_code,integration_mode,status,last_seen_at,updated_at FROM bf_gate_device";
    }

    private GateDeviceResponse map(ResultSet rs, int row) throws SQLException {
        return new GateDeviceResponse(rs.getLong("id"), rs.getString("device_no"), rs.getString("name"),
                rs.getString("venue_code"), rs.getString("integration_mode"), rs.getString("status"),
                rs.getObject("last_seen_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class), null);
    }

    private GateDeviceResponse withSecret(GateDeviceResponse value, String secret) {
        return new GateDeviceResponse(value.id(), value.deviceNo(), value.name(), value.venueCode(),
                value.integrationMode(), value.status(), value.lastSeenAt(), value.updatedAt(), secret);
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long ensureUser(AuthenticatedPrincipal principal) {
        jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') " +
                        "ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",
                principal.databaseOpenId(), principal.displayName());
        Long value = jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class,
                principal.databaseOpenId());
        if (value == null) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "无法识别当前管理员");
        return value;
    }

    private void audit(long operator, String action, long id) {
        jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) " +
                        "VALUES (?,?,'GATE_DEVICE',?,JSON_OBJECT())",
                operator, action, String.valueOf(id));
    }

    private String code(String value, int max, String label) {
        String result = text(value, max, label).toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9_-]+")) throw invalid(label + "仅支持字母、数字、下划线和连字符");
        return result;
    }

    private String optionalCode(String value, int max, String label) {
        if (!StringUtils.hasText(value)) return null;
        String result = code(value, max, label);
        Integer siteCount = jdbc.queryForObject("SELECT COUNT(*) FROM bf_business_site WHERE site_code=?", Integer.class, result);
        Integer storeCount = jdbc.queryForObject("SELECT COUNT(*) FROM bf_dining_store WHERE store_code=?", Integer.class, result);
        if ((siteCount == null ? 0 : siteCount) + (storeCount == null ? 0 : storeCount) == 0) {
            throw invalid("场馆编码必须是已存在的业务站点或餐饮门店编码");
        }
        return result;
    }

    private String text(String value, int max, String label) {
        if (!StringUtils.hasText(value) || value.trim().length() > max) {
            throw invalid(label + "不能为空且最长" + max + "字符");
        }
        return value.trim();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
    }
}
