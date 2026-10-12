package com.iminiapp.backend.identity.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class AdminBootstrapInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final AdminLoginService loginService;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminBootstrapInitializer(JdbcTemplate jdbcTemplate, AdminLoginService loginService,
            @Value("${app.admin-auth.bootstrap-username:}") String username,
            @Value("${app.admin-auth.bootstrap-password:}") String password,
            @Value("${app.admin-auth.bootstrap-display-name:系统管理员}") String displayName) {
        this.jdbcTemplate = jdbcTemplate;
        this.loginService = loginService;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) return;
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_admin_account", Integer.class);
        if (count != null && count > 0) return;
        if (!AdminPasswordPolicy.isStrong(password)) {
            throw new IllegalStateException(
                    "ADMIN_BOOTSTRAP_PASSWORD 必须为 12—128 位、同时包含字母和数字，且不能为简单重复字符");
        }
        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        if (!normalizedUsername.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_USERNAME 格式不正确");
        }
        jdbcTemplate.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE')",
                "admin:" + normalizedUsername, displayName.trim());
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class,
                "admin:" + normalizedUsername);
        jdbcTemplate.update("""
                INSERT INTO bf_admin_account(user_id,username,password_hash) VALUES (?,?,?)
                """, userId, normalizedUsername, loginService.encodePassword(password));
        jdbcTemplate.update("""
                INSERT INTO bf_user_role(user_id,role_id)
                SELECT ?,id FROM bf_role WHERE code='ADMIN'
                """, userId);
    }
}
