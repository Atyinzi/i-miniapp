package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class NotificationAdminQueryService {
    private final NotificationRepository repository;

    public NotificationAdminQueryService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public NotificationTaskPage tasks(NotificationTaskStatus status, NotificationChannel channel,
                                      String eventType, int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "分页参数不正确");
        }
        String normalizedEvent = StringUtils.hasText(eventType)
                ? eventType.trim().toUpperCase(Locale.ROOT) : null;
        if (normalizedEvent != null && normalizedEvent.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "事件类型最多64个字符");
        }
        return repository.queryTasks(status, channel, normalizedEvent, page, size);
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> deliveries(long taskId) {
        repository.findTask(taskId).orElseThrow(
                () -> new BusinessException(CommonErrorCode.NOT_FOUND, "通知任务不存在"));
        return repository.deliveries(taskId);
    }

    @Transactional
    public int requeueConfigured(NotificationChannel channel, String eventType, String templateCode, int limit) {
        if (channel == null || !StringUtils.hasText(eventType) || !StringUtils.hasText(templateCode)
                || templateCode.trim().length() > 64 || !templateCode.trim().matches("[A-Za-z0-9_-]+")
                || limit < 1 || limit > 1000) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "通知重排参数不正确");
        }
        return repository.requeueWaitingConfiguration(channel, eventType.trim().toUpperCase(Locale.ROOT),
                templateCode.trim(), limit);
    }
}
