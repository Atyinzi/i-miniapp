package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.security.SensitiveDataCipher;
import com.iminiapp.backend.vehicle.api.VehicleDepartmentResponse;
import com.iminiapp.backend.vehicle.api.VehicleDepartmentUpsertRequest;
import com.iminiapp.backend.vehicle.api.VehicleEmployeeAccountBindingRequest;
import com.iminiapp.backend.vehicle.api.VehicleEmployeeCreateRequest;
import com.iminiapp.backend.vehicle.api.VehicleEmployeeProfileResponse;
import com.iminiapp.backend.vehicle.api.VehicleEmployeeResponse;
import com.iminiapp.backend.vehicle.api.VehicleEmployeeSelfBindRequest;
import com.iminiapp.backend.vehicle.api.VehicleDriverResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class VehicleEmployeeService {
    private final JdbcTemplate jdbc;
    private final SensitiveDataCipher cipher;
    private final VehicleSensitiveDataReader sensitiveReader;

    public VehicleEmployeeService(JdbcTemplate jdbc, SensitiveDataCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.sensitiveReader = new VehicleSensitiveDataReader(cipher);
    }

    @Transactional(readOnly = true)
    public VehicleEmployeeProfileResponse profile(AuthenticatedPrincipal principal) {
        Long userId = existingUser(principal);
        if (userId == null) return VehicleEmployeeProfileResponse.notBound();
        List<VehicleEmployeeProfileResponse> rows = jdbc.query("""
                SELECT e.id,e.employee_no,e.name_cipher,e.department_id,d.department_code,d.department_name,e.status
                FROM bf_employee e JOIN bf_department d ON d.id=e.department_id WHERE e.user_id=?
                """, (rs, n) -> new VehicleEmployeeProfileResponse(true, rs.getLong("id"),
                rs.getString("employee_no"), sensitiveReader.maskedName(rs.getBytes("name_cipher")),
                rs.getLong("department_id"), rs.getString("department_code"),
                rs.getString("department_name"), rs.getString("status")), userId);
        return rows.stream().findFirst().orElseGet(VehicleEmployeeProfileResponse::notBound);
    }

    @Transactional
    public VehicleEmployeeProfileResponse selfBind(AuthenticatedPrincipal principal,
                                                   VehicleEmployeeSelfBindRequest request) {
        String employeeNo = required(request == null ? null : request.employeeNo(), "employeeNo", 50).toUpperCase(Locale.ROOT);
        long userId = requireExistingUser(principal);
        String mobile = jdbc.queryForObject("SELECT mobile FROM bf_user WHERE id=? FOR UPDATE", String.class, userId);
        if (!StringUtils.hasText(mobile)) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "请先通过微信完成手机号授权，再绑定员工身份");
        }
        List<EmployeeBinding> rows = jdbc.query("""
                SELECT id,user_id,status,mobile_hash FROM bf_employee WHERE employee_no=? FOR UPDATE
                """, (rs, n) -> new EmployeeBinding(rs.getLong("id"), nullableLong(rs, "user_id"),
                rs.getString("status"), rs.getString("mobile_hash")), employeeNo);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "员工档案不存在");
        EmployeeBinding employee = rows.get(0);
        if (!"ACTIVE".equals(employee.status())) throw new BusinessException(CommonErrorCode.FORBIDDEN, "员工档案已停用");
        if (!employee.mobileHash().equals(cipher.searchHash(mobile.trim()))) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "微信手机号与员工档案不一致");
        }
        if (employee.userId() != null && employee.userId() != userId) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "该员工档案已绑定其他微信账号");
        }
        ensureUserNotBoundToOtherEmployee(userId, employee.id());
        jdbc.update("UPDATE bf_employee SET user_id=?,bound_at=CURRENT_TIMESTAMP WHERE id=?", userId, employee.id());
        assignRole(userId, "EMPLOYEE");
        audit(userId, "VEHICLE_EMPLOYEE_SELF_BIND", "EMPLOYEE", employee.id());
        return profile(principal);
    }

    @Transactional(readOnly = true)
    public long requireActiveEmployee(AuthenticatedPrincipal principal) {
        long userId = requireBoundEmployee(principal);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_employee e JOIN bf_department d ON d.id=e.department_id
                WHERE e.user_id=? AND e.status='ACTIVE' AND d.status='ACTIVE'
                """, Integer.class, userId);
        if (count == null || count == 0) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "仅在职且部门有效的员工可以申请内部用车");
        }
        return userId;
    }

    @Transactional(readOnly = true)
    public long requireBoundEmployee(AuthenticatedPrincipal principal) {
        Long userId = existingUser(principal);
        if (userId == null) throw new BusinessException(CommonErrorCode.FORBIDDEN, "当前账号未绑定内部员工档案");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bf_employee WHERE user_id=?", Integer.class, userId);
        if (count == null || count == 0) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "当前账号未绑定内部员工档案");
        }
        return userId;
    }

    @Transactional(readOnly = true)
    public List<VehicleDepartmentResponse> departments() {
        return jdbc.query("SELECT id,department_code,department_name,parent_id,status FROM bf_department ORDER BY status,id",
                (rs, n) -> department(rs));
    }

    @Transactional
    public VehicleDepartmentResponse createDepartment(AuthenticatedPrincipal operator,
                                                      VehicleDepartmentUpsertRequest request) {
        if (request == null) throw invalid("请填写部门信息");
        String code = required(request.departmentCode(), "departmentCode", 50).toUpperCase(Locale.ROOT);
        String name = required(request.departmentName(), "departmentName", 100);
        String status = status(request.status());
        if (request.parentId() != null && count("SELECT COUNT(*) FROM bf_department WHERE id=?", request.parentId()) == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "上级部门不存在");
        }
        if (count("SELECT COUNT(*) FROM bf_department WHERE department_code=?", code) > 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "部门编码已存在");
        }
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_department(department_code,department_name,parent_id,status) VALUES (?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, code);
            statement.setString(2, name);
            if (request.parentId() == null) statement.setNull(3, java.sql.Types.BIGINT);
            else statement.setLong(3, request.parentId());
            statement.setString(4, status);
            return statement;
        }, holder);
        long id = generatedId(holder);
        audit(operatorId(operator), "VEHICLE_DEPARTMENT_CREATE", "DEPARTMENT", id);
        return findDepartment(id);
    }

    @Transactional(readOnly = true)
    public List<VehicleEmployeeResponse> employees(String requestedStatus) {
        String normalized = normalizeOptionalStatus(requestedStatus);
        String sql = employeeSelect() + (normalized == null ? " ORDER BY e.id DESC" : " WHERE e.status=? ORDER BY e.id DESC");
        return normalized == null ? jdbc.query(sql, this::employee) : jdbc.query(sql, this::employee, normalized);
    }

    @Transactional(readOnly = true)
    public List<VehicleDriverResponse> driverAccounts() {
        return jdbc.query("SELECT id,driver_no,user_id,status FROM bf_driver ORDER BY id DESC",
                (rs, n) -> new VehicleDriverResponse(rs.getLong("id"), rs.getString("driver_no"),
                        nullableLong(rs, "user_id"), rs.getString("status")));
    }

    @Transactional
    public VehicleEmployeeResponse createEmployee(AuthenticatedPrincipal operator,
                                                  VehicleEmployeeCreateRequest request) {
        if (request == null || request.departmentId() == null) throw invalid("请填写员工和部门信息");
        String employeeNo = required(request.employeeNo(), "employeeNo", 50).toUpperCase(Locale.ROOT);
        String name = required(request.name(), "name", 64);
        String mobile = normalizeMobile(request.mobile());
        String requestedStatus = status(request.status());
        if (count("SELECT COUNT(*) FROM bf_department WHERE id=? AND status='ACTIVE'", request.departmentId()) == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "部门不存在或已停用");
        }
        if (count("SELECT COUNT(*) FROM bf_employee WHERE employee_no=? OR mobile_hash=?", employeeNo, cipher.searchHash(mobile)) > 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "员工工号或手机号已存在");
        }
        if (request.userId() != null) validateBindableUser(request.userId(), null);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_employee(user_id,department_id,employee_no,name_cipher,mobile_cipher,mobile_hash,status,bound_at)
                    VALUES (?,?,?,?,?,?,?,CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (request.userId() == null) statement.setNull(1, java.sql.Types.BIGINT); else statement.setLong(1, request.userId());
            statement.setLong(2, request.departmentId()); statement.setString(3, employeeNo);
            statement.setBytes(4, cipher.encrypt(name)); statement.setBytes(5, cipher.encrypt(mobile));
            statement.setString(6, cipher.searchHash(mobile)); statement.setString(7, requestedStatus);
            if (request.userId() == null) statement.setNull(8, java.sql.Types.BIGINT); else statement.setLong(8, request.userId());
            return statement;
        }, holder);
        long id = generatedId(holder);
        if (request.userId() != null) assignRole(request.userId(), "EMPLOYEE");
        audit(operatorId(operator), "VEHICLE_EMPLOYEE_CREATE", "EMPLOYEE", id);
        return findEmployee(id);
    }

    @Transactional
    public VehicleEmployeeResponse bindAccount(AuthenticatedPrincipal operator, long employeeId,
                                               VehicleEmployeeAccountBindingRequest request) {
        if (request == null || request.userId() == null) throw invalid("userId 不能为空");
        validateBindableUser(request.userId(), employeeId);
        if (jdbc.update("UPDATE bf_employee SET user_id=?,bound_at=CURRENT_TIMESTAMP WHERE id=?",
                request.userId(), employeeId) != 1) throw new BusinessException(CommonErrorCode.NOT_FOUND, "员工档案不存在");
        assignRole(request.userId(), "EMPLOYEE");
        audit(operatorId(operator), "VEHICLE_EMPLOYEE_ACCOUNT_BIND", "EMPLOYEE", employeeId);
        return findEmployee(employeeId);
    }

    @Transactional
    public VehicleEmployeeResponse unbindAccount(AuthenticatedPrincipal operator, long employeeId) {
        VehicleEmployeeResponse employee = findEmployee(employeeId);
        if (employee.userId() == null) throw new BusinessException(CommonErrorCode.CONFLICT, "员工档案尚未绑定账号");
        jdbc.update("UPDATE bf_employee SET user_id=NULL,bound_at=NULL WHERE id=?", employeeId);
        removeRole(employee.userId(), "EMPLOYEE");
        audit(operatorId(operator), "VEHICLE_EMPLOYEE_ACCOUNT_UNBIND", "EMPLOYEE", employeeId);
        return findEmployee(employeeId);
    }

    @Transactional
    public VehicleEmployeeResponse updateStatus(AuthenticatedPrincipal operator, long employeeId,
                                                String requestedStatus) {
        String normalized = status(requestedStatus);
        if (jdbc.update("UPDATE bf_employee SET status=? WHERE id=?", normalized, employeeId) != 1) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "员工档案不存在");
        }
        audit(operatorId(operator), "VEHICLE_EMPLOYEE_STATUS_UPDATE", "EMPLOYEE", employeeId);
        return findEmployee(employeeId);
    }

    private VehicleEmployeeResponse findEmployee(long id) {
        List<VehicleEmployeeResponse> rows = jdbc.query(employeeSelect() + " WHERE e.id=?", this::employee, id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "员工档案不存在");
        return rows.get(0);
    }
    private String employeeSelect() { return """
            SELECT e.id,e.employee_no,e.name_cipher,e.mobile_cipher,e.department_id,d.department_code,
                   d.department_name,e.user_id,e.status,e.bound_at
            FROM bf_employee e JOIN bf_department d ON d.id=e.department_id
            """; }
    private VehicleEmployeeResponse employee(ResultSet rs, int row) throws SQLException {
        return new VehicleEmployeeResponse(rs.getLong("id"), rs.getString("employee_no"),
                sensitiveReader.maskedName(rs.getBytes("name_cipher")),
                sensitiveReader.maskedMobile(rs.getBytes("mobile_cipher")), rs.getLong("department_id"),
                rs.getString("department_code"), rs.getString("department_name"), nullableLong(rs, "user_id"),
                rs.getString("status"), rs.getObject("bound_at", LocalDateTime.class));
    }
    private VehicleDepartmentResponse findDepartment(long id) {
        return jdbc.queryForObject("SELECT id,department_code,department_name,parent_id,status FROM bf_department WHERE id=?",
                (rs, n) -> department(rs), id);
    }
    private VehicleDepartmentResponse department(ResultSet rs) throws SQLException {
        return new VehicleDepartmentResponse(rs.getLong("id"), rs.getString("department_code"),
                rs.getString("department_name"), nullableLong(rs, "parent_id"), rs.getString("status"));
    }
    private void validateBindableUser(long userId, Long employeeId) {
        if (count("SELECT COUNT(*) FROM bf_user WHERE id=? AND status='ACTIVE'", userId) == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "用户不存在或已停用");
        }
        Integer conflicts = employeeId == null
                ? count("SELECT COUNT(*) FROM bf_employee WHERE user_id=?", userId)
                : count("SELECT COUNT(*) FROM bf_employee WHERE user_id=? AND id<>?", userId, employeeId);
        if (conflicts > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "该用户已绑定其他员工档案");
    }
    private void ensureUserNotBoundToOtherEmployee(long userId, long employeeId) {
        if (count("SELECT COUNT(*) FROM bf_employee WHERE user_id=? AND id<>?", userId, employeeId) > 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "当前微信账号已绑定其他员工档案");
        }
    }
    private Long existingUser(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'",
                Long.class, principal.databaseOpenId());
        return ids.isEmpty() ? null : ids.get(0);
    }
    private long requireExistingUser(AuthenticatedPrincipal principal) {
        Long id = existingUser(principal);
        if (id == null) throw new BusinessException(CommonErrorCode.NOT_FOUND, "用户资料不存在，请重新登录");
        return id;
    }
    private long operatorId(AuthenticatedPrincipal principal) {
        if ("ADMIN".equals(principal.accountType())) {
            try { return Long.parseLong(principal.userId()); }
            catch (RuntimeException exception) { throw new BusinessException(CommonErrorCode.FORBIDDEN, "管理员身份无效"); }
        }
        return requireExistingUser(principal);
    }
    private void assignRole(long userId, String roleCode) {
        jdbc.update("""
                INSERT IGNORE INTO bf_user_role(user_id,role_id) SELECT ?,id FROM bf_role WHERE code=?
                """, userId, roleCode);
    }
    private void removeRole(long userId, String roleCode) {
        jdbc.update("DELETE ur FROM bf_user_role ur JOIN bf_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code=?",
                userId, roleCode);
    }
    private void audit(long operatorId, String action, String targetType, long targetId) {
        jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,?,?,JSON_OBJECT())",
                operatorId, action, targetType, String.valueOf(targetId));
    }
    private String status(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "ACTIVE";
        if (!Set.of("ACTIVE", "INACTIVE").contains(normalized)) throw invalid("状态仅支持 ACTIVE 或 INACTIVE");
        return normalized;
    }
    private String normalizeOptionalStatus(String value) {
        return StringUtils.hasText(value) ? status(value) : null;
    }
    private String normalizeMobile(String value) {
        String mobile = required(value, "mobile", 32).replace(" ", "");
        if (!mobile.matches("\\+?[0-9]{7,20}")) throw invalid("手机号格式不正确");
        return mobile;
    }
    private String required(String value, String field, int max) {
        if (!StringUtils.hasText(value) || value.trim().length() > max) throw invalid(field + " 不能为空且最长" + max + "字符");
        return value.trim();
    }
    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
    private long generatedId(KeyHolder holder) {
        if (holder.getKey() == null) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "数据创建失败");
        return holder.getKey().longValue();
    }
    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
    }
    private record EmployeeBinding(long id, Long userId, String status, String mobileHash) {}
}
