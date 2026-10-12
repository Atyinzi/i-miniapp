package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.api.AdminAccountCreateRequest;
import com.iminiapp.backend.identity.api.AdminAccountResponse;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AdminAccountManagementService {
    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "OPERATIONS", "VERIFIER");
    private final JdbcTemplate jdbcTemplate;
    private final AdminLoginService loginService;
    private final AccessTokenService accessTokenService;

    public AdminAccountManagementService(JdbcTemplate jdbcTemplate, AdminLoginService loginService,
            AccessTokenService accessTokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.loginService = loginService;
        this.accessTokenService = accessTokenService;
    }

    @Transactional(readOnly = true)
    public List<AdminAccountResponse> list() {
        return jdbcTemplate.query("""
                SELECT a.id,a.user_id,a.username,u.nickname,a.status,a.locked_until,a.last_login_at,a.last_login_ip,a.created_at
                FROM bf_admin_account a JOIN bf_user u ON u.id=a.user_id ORDER BY a.id
                """, (rs, n) -> new AdminAccountResponse(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("username"), rs.getString("nickname"), rs.getString("status"),
                roles(rs.getLong("user_id")), rs.getObject("locked_until", LocalDateTime.class),
                rs.getObject("last_login_at", LocalDateTime.class), rs.getString("last_login_ip"),
                rs.getObject("created_at", LocalDateTime.class)));
    }

    @Transactional
    public AdminAccountResponse create(AuthenticatedPrincipal operator, AdminAccountCreateRequest request) {
        if (request == null) throw invalid("请填写管理员账号信息");
        String username = normalizeUsername(request.username());
        String displayName = StringUtils.hasText(request.displayName()) ? request.displayName().trim() : "管理员";
        validatePassword(request.password());
        List<String> roleCodes = validateRoles(request.roles());
        if (displayName.length() > 64) throw invalid("显示名称最长为64个字符");
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_admin_account WHERE username=?", Integer.class, username);
        if (exists != null && exists > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "管理员用户名已存在");
        jdbcTemplate.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE')", "admin:" + username, displayName);
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, "admin:" + username);
        jdbcTemplate.update("INSERT INTO bf_admin_account(user_id,username,password_hash) VALUES (?,?,?)",
                userId, username, loginService.encodePassword(request.password()));
        replaceRoles(userId, roleCodes);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM bf_admin_account WHERE user_id=?", Long.class, userId);
        audit(operator, "ADMIN_ACCOUNT_CREATE", id, username);
        return find(id);
    }

    @Transactional
    public AdminAccountResponse updateStatus(AuthenticatedPrincipal operator, long id, String requestedStatus) {
        String status = StringUtils.hasText(requestedStatus) ? requestedStatus.trim().toUpperCase(Locale.ROOT) : "";
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) throw invalid("账号状态仅支持 ACTIVE 或 DISABLED");
        AccountRef account = account(id);
        if (account.userId() == operatorId(operator) && "DISABLED".equals(status)) throw invalid("不能停用当前登录账号");
        jdbcTemplate.update("UPDATE bf_admin_account SET status=?,failed_login_attempts=0,locked_until=NULL WHERE id=?", status, id);
        jdbcTemplate.update("UPDATE bf_user SET status=? WHERE id=?", status, account.userId());
        if ("DISABLED".equals(status)) accessTokenService.revokeAll(String.valueOf(account.userId()));
        audit(operator, "ADMIN_ACCOUNT_STATUS_UPDATE", id, status);
        return find(id);
    }

    @Transactional
    public void resetPassword(AuthenticatedPrincipal operator, long id, String password) {
        validatePassword(password);
        AccountRef account = account(id);
        jdbcTemplate.update("""
                UPDATE bf_admin_account SET password_hash=?,password_changed_at=CURRENT_TIMESTAMP,
                failed_login_attempts=0,locked_until=NULL WHERE id=?
                """, loginService.encodePassword(password), id);
        accessTokenService.revokeAll(String.valueOf(account.userId()));
        audit(operator, "ADMIN_ACCOUNT_PASSWORD_RESET", id, "password-reset");
    }

    @Transactional
    public AdminAccountResponse updateRoles(AuthenticatedPrincipal operator, long id, List<String> requestedRoles) {
        AccountRef account = account(id);
        List<String> roles = validateRoles(requestedRoles);
        if (account.userId() == operatorId(operator) && !roles.contains("ADMIN")) throw invalid("当前账号必须保留系统管理员角色");
        replaceRoles(account.userId(), roles);
        accessTokenService.revokeAll(String.valueOf(account.userId()));
        audit(operator, "ADMIN_ACCOUNT_ROLES_UPDATE", id, String.join(",", roles));
        return find(id);
    }

    private AdminAccountResponse find(long id) {
        return list().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "管理员账号不存在"));
    }
    private AccountRef account(long id) {
        List<AccountRef> rows = jdbcTemplate.query("SELECT user_id,username FROM bf_admin_account WHERE id=? FOR UPDATE",
                (rs, n) -> new AccountRef(rs.getLong("user_id"), rs.getString("username")), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "管理员账号不存在");
        return rows.get(0);
    }
    private List<String> roles(long userId) {
        return jdbcTemplate.queryForList("SELECT r.code FROM bf_role r JOIN bf_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.code", String.class, userId);
    }
    private void replaceRoles(long userId, List<String> roles) {
        jdbcTemplate.update("DELETE FROM bf_user_role WHERE user_id=?", userId);
        for (String role : roles) jdbcTemplate.update("INSERT INTO bf_user_role(user_id,role_id) SELECT ?,id FROM bf_role WHERE code=?", userId, role);
    }
    private List<String> validateRoles(List<String> requested) {
        if (requested == null || requested.isEmpty()) throw invalid("至少分配一个角色");
        List<String> roles = new LinkedHashSet<>(requested.stream().map(v -> v.trim().toUpperCase(Locale.ROOT)).toList()).stream().toList();
        if (!ALLOWED_ROLES.containsAll(roles)) throw invalid("包含不支持的管理员角色");
        return roles;
    }
    private String normalizeUsername(String value) {
        String username = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
        if (!username.matches("[a-z0-9][a-z0-9._-]{2,63}")) throw invalid("用户名需为3—64位字母、数字、点、横线或下划线");
        return username;
    }
    private void validatePassword(String password) {
        if (!AdminPasswordPolicy.isStrong(password)) {
            throw invalid("密码需为12—128位、同时包含字母和数字，且不能为简单重复字符");
        }
    }
    private long operatorId(AuthenticatedPrincipal operator) {
        try { return Long.parseLong(operator.userId()); }
        catch (Exception e) { throw new BusinessException(CommonErrorCode.FORBIDDEN, "管理员身份无效"); }
    }
    private void audit(AuthenticatedPrincipal operator, String action, long targetId, String detail) {
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?, 'ADMIN_ACCOUNT', ?, JSON_OBJECT('value',?))
                """, operatorId(operator), action, String.valueOf(targetId), detail);
    }
    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }
    private record AccountRef(long userId, String username) {}
}
