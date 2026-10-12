package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.api.AccountCancellationRequest;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final AccessTokenService accessTokenService;

    public AccountService(JdbcTemplate jdbc, AccessTokenService accessTokenService) {
        this.jdbc = jdbc;
        this.accessTokenService = accessTokenService;
    }

    @Transactional
    public void cancel(AuthenticatedPrincipal principal, AccountCancellationRequest request) {
        if (request == null || !"DELETE".equals(request.confirmation())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "请确认注销账号");
        }
        Long userId = jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'",
                Long.class, principal.databaseOpenId());
        if (userId == null) throw new BusinessException(CommonErrorCode.NOT_FOUND, "账号不存在或已经注销");

        Long balance = jdbc.queryForObject("SELECT COALESCE(MAX(balance_cent),0) FROM bf_member_account WHERE user_id=?",
                Long.class, userId);
        if (balance != null && balance > 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "会员余额不为零，请处理余额后再注销");
        }
        Integer activeOrders = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_order WHERE user_id=?
                AND status NOT IN ('COMPLETED','CANCELLED','REFUNDED','CLOSED')
                """, Integer.class, userId);
        if (activeOrders != null && activeOrders > 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "仍有未完成订单，请处理完成后再注销");
        }

        jdbc.update("UPDATE bf_member_account SET status='INACTIVE',points=0,growth_value=0,valid_until=NULL,version=version+1 WHERE user_id=?", userId);
        jdbc.update("""
                UPDATE bf_user SET wechat_openid=NULL,unionid=NULL,mobile=NULL,avatar_url=NULL,
                nickname=CONCAT('已注销用户',id),real_name=NULL,status='CANCELLED' WHERE id=?
                """, userId);
        jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,'ACCOUNT_CANCEL','USER',?,JSON_OBJECT('source','MINIPROGRAM'))",
                userId, String.valueOf(userId));
        accessTokenService.revokeAll(principal.userId());
        accessTokenService.revokeAllByExternalIdentity(principal.identityProvider(), principal.externalIdentity());
    }
}
