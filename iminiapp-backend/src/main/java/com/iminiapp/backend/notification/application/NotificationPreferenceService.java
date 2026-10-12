package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.notification.api.NotificationApiModels.PreferenceItem;
import com.iminiapp.backend.notification.domain.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class NotificationPreferenceService {
    private static final Pattern EVENT = Pattern.compile("^[A-Z0-9][A-Z0-9_.-]{0,63}$");
    private final NotificationRepository repository;

    public NotificationPreferenceService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<NotificationPreference> preferences(AuthenticatedPrincipal principal) {
        long userId = userId(principal);
        List<NotificationPreference> stored = new ArrayList<>(repository.preferences(userId));
        Set<NotificationChannel> wildcardChannels = new HashSet<>();
        stored.stream().filter(value -> "*".equals(value.eventType()))
                .forEach(value -> wildcardChannels.add(value.channel()));
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (!wildcardChannels.contains(channel)) {
                stored.add(new NotificationPreference("*", channel, true));
            }
        }
        stored.sort(Comparator.comparing(NotificationPreference::eventType)
                .thenComparing(value -> value.channel().name()));
        return List.copyOf(stored);
    }

    @Transactional
    public List<NotificationPreference> update(AuthenticatedPrincipal principal, List<PreferenceItem> updates) {
        long userId = userId(principal);
        if (updates == null || updates.isEmpty() || updates.size() > 100) {
            throw invalid("通知偏好需包含1到100项");
        }
        Set<String> unique = new HashSet<>();
        for (PreferenceItem update : updates) {
            if (update == null || update.channel() == null) throw invalid("通知偏好渠道不能为空");
            String eventType = normalizeEvent(update.eventType());
            if (!unique.add(eventType + "|" + update.channel())) throw invalid("通知偏好存在重复项");
            repository.upsertPreference(userId, eventType, update.channel(), update.enabled());
        }
        return preferences(principal);
    }

    private long userId(AuthenticatedPrincipal principal) {
        if (principal == null || !StringUtils.hasText(principal.databaseOpenId())) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        return repository.findUserIdByOpenId(principal.databaseOpenId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户不存在"));
    }

    private String normalizeEvent(String value) {
        if (!StringUtils.hasText(value)) throw invalid("事件类型不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!"*".equals(normalized) && !EVENT.matcher(normalized).matches()) {
            throw invalid("事件类型格式不正确");
        }
        return normalized;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
    }
}
