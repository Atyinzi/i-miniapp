package com.iminiapp.backend.identity.api;

import java.time.LocalDateTime;
import java.util.List;

public record AdminAccountResponse(long id, long userId, String username, String displayName,
        String status, List<String> roles, LocalDateTime lockedUntil, LocalDateTime lastLoginAt,
        String lastLoginIp, LocalDateTime createdAt) {
}
