package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.api.WechatUserProfileResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class WechatUserProfileService {
    private final JdbcTemplate jdbcTemplate;

    public WechatUserProfileService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void recordLogin(String openId, String unionId, String displayName, String avatarUrl) {
        jdbcTemplate.update("""
                INSERT INTO bf_user(wechat_openid, unionid, nickname, avatar_url, status, last_login_at)
                VALUES (?, NULLIF(?, ''), ?, NULLIF(?, ''), 'ACTIVE', NOW())
                ON DUPLICATE KEY UPDATE
                  unionid = COALESCE(VALUES(unionid), unionid),
                  nickname = IF(nickname IS NULL OR nickname = '' OR nickname = '微信用户', VALUES(nickname), nickname),
                  avatar_url = COALESCE(avatar_url, VALUES(avatar_url)),
                  last_login_at = NOW()
                """, openId, value(unionId), normalizeNickname(displayName), safeAvatarUrl(avatarUrl));
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'",
                Integer.class, openId);
        if (active == null || active == 0) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "账号已停用，请联系管理员");
        }
    }

    public WechatUserProfileResponse profile(String userId, String openId) {
        List<WechatUserProfileResponse> rows = jdbcTemplate.query("""
                SELECT nickname, avatar_url, mobile
                FROM bf_user WHERE wechat_openid = ?
                """, (rs, rowNum) -> new WechatUserProfileResponse(
                userId,
                rs.getString("nickname"),
                value(rs.getString("avatar_url")),
                value(rs.getString("mobile")),
                "WECHAT"), openId);
        if (rows.isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "微信用户资料不存在");
        }
        return rows.get(0);
    }

    @Transactional
    public WechatUserProfileResponse updateProfile(
            String userId, String openId, String nickname, String avatarUrl) {
        int updated = jdbcTemplate.update("""
                UPDATE bf_user SET nickname = ?, avatar_url = NULLIF(?, '')
                WHERE wechat_openid = ?
                """, normalizeNickname(nickname), value(avatarUrl), openId);
        if (updated == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "微信用户资料不存在");
        }
        return profile(userId, openId);
    }

    @Transactional
    public void updateMobile(String openId, String mobile) {
        if (!StringUtils.hasText(mobile)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "微信手机号不能为空");
        }
        int updated = jdbcTemplate.update(
                "UPDATE bf_user SET mobile = ? WHERE wechat_openid = ?", mobile.trim(), openId);
        if (updated == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "微信用户资料不存在");
        }
    }

    private String normalizeNickname(String nickname) {
        String value = StringUtils.hasText(nickname) ? nickname.trim() : "微信用户";
        if (value.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "昵称最长为 64 个字符");
        }
        return value;
    }

    private String value(String source) {
        return source == null ? "" : source;
    }

    private String safeAvatarUrl(String avatarUrl) {
        String value = value(avatarUrl).trim();
        if (value.length() > 2048) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "头像地址最长为 2048 个字符");
        }
        return value;
    }
}
