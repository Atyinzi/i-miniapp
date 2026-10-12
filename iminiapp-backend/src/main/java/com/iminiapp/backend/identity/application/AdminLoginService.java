package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.api.AdminLoginRequest;
import com.iminiapp.backend.identity.api.LoginResponse;
import com.iminiapp.backend.identity.domain.IdentityPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class AdminLoginService {
    private final JdbcTemplate jdbcTemplate;
    private final AccessTokenService accessTokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final int maxFailedAttempts;
    private final Duration lockDuration;

    public AdminLoginService(JdbcTemplate jdbcTemplate, AccessTokenService accessTokenService,
            @Value("${app.admin-auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${app.admin-auth.lock-duration:15m}") Duration lockDuration) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessTokenService = accessTokenService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse login(AdminLoginRequest request, String ipAddress) {
        if (request == null || !StringUtils.hasText(request.username()) || !StringUtils.hasText(request.password())) {
            throw unauthorized();
        }
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (username.length() > 64 || request.password().length() > 128) throw unauthorized();
        List<AccountRow> rows = jdbcTemplate.query("""
                SELECT a.id,a.user_id,a.password_hash,a.status,a.failed_login_attempts,a.locked_until,u.nickname,u.status user_status
                FROM bf_admin_account a JOIN bf_user u ON u.id=a.user_id
                WHERE a.username=? FOR UPDATE
                """, (rs, n) -> new AccountRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("password_hash"), rs.getString("status"), rs.getInt("failed_login_attempts"),
                rs.getObject("locked_until", LocalDateTime.class), rs.getString("nickname"),
                rs.getString("user_status")), username);
        if (rows.isEmpty()) throw unauthorized();
        AccountRow account = rows.get(0);
        LocalDateTime now = LocalDateTime.now();
        if (!"ACTIVE".equals(account.status()) || !"ACTIVE".equals(account.userStatus())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "管理员账号已停用，请联系系统管理员");
        }
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) {
            throw new BusinessException(CommonErrorCode.RATE_LIMITED, "登录失败次数过多，请稍后再试");
        }
        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            int failed = account.failedAttempts() + 1;
            LocalDateTime lockedUntil = failed >= maxFailedAttempts ? now.plus(lockDuration) : null;
            jdbcTemplate.update("UPDATE bf_admin_account SET failed_login_attempts=?,locked_until=? WHERE id=?",
                    failed >= maxFailedAttempts ? 0 : failed, lockedUntil, account.id());
            throw unauthorized();
        }
        List<String> roles = jdbcTemplate.queryForList("""
                SELECT r.code FROM bf_role r JOIN bf_user_role ur ON ur.role_id=r.id
                WHERE ur.user_id=? ORDER BY r.code
                """, String.class, account.userId());
        List<String> permissions = jdbcTemplate.queryForList("""
                SELECT DISTINCT p.code FROM bf_permission p
                JOIN bf_role_permission rp ON rp.permission_id=p.id
                JOIN bf_user_role ur ON ur.role_id=rp.role_id WHERE ur.user_id=? ORDER BY p.code
                """, String.class, account.userId());
        if (roles.isEmpty()) throw new BusinessException(CommonErrorCode.FORBIDDEN, "管理员账号尚未分配角色");
        jdbcTemplate.update("""
                UPDATE bf_admin_account SET failed_login_attempts=0,locked_until=NULL,last_login_at=?,last_login_ip=? WHERE id=?
                """, now, ipAddress, account.id());
        jdbcTemplate.update("UPDATE bf_user SET last_login_at=? WHERE id=?", now, account.userId());
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail,ip_address)
                VALUES (?,'ADMIN_LOGIN','ADMIN_ACCOUNT',?,JSON_OBJECT('username',?),?)
                """, account.userId(), String.valueOf(account.id()), username, ipAddress);
        return LoginResponse.from(accessTokenService.issue(new IdentityPrincipal(
                String.valueOf(account.userId()), account.displayName(), "ADMIN", roles,
                "PASSWORD", username, permissions)));
    }

    public String encodePassword(String rawPassword) { return passwordEncoder.encode(rawPassword); }

    private BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }

    private record AccountRow(long id, long userId, String passwordHash, String status,
            int failedAttempts, LocalDateTime lockedUntil, String displayName, String userStatus) {}
}
