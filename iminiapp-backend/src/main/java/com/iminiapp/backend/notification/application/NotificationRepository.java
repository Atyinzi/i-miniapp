package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository {
    long insertTask(long userId, String businessType, String businessRef, String eventType,
                    NotificationChannel channel, String templateCode, String deduplicationKey,
                    String payloadJson, NotificationTaskStatus status, int maxAttempts);

    Optional<NotificationTask> findByDeduplicationKey(String key);
    Optional<NotificationTask> findTask(long id);
    List<Long> findDueTaskIds(LocalDateTime now, int limit);
    boolean claim(long taskId, LocalDateTime now);
    int recoverStaleProcessing(LocalDateTime staleBefore, LocalDateTime retryAt);
    int nextDeliveryAttempt(long taskId);
    int countRecentSent(long userId, NotificationChannel channel, LocalDateTime since);
    Optional<String> findRecipient(long userId, NotificationChannel channel);

    void markWaitingConfiguration(long taskId, String code, String message);
    void markRetry(long taskId, int attemptCount, LocalDateTime nextAttemptAt, String code, String message);
    void markFailed(long taskId, int attemptCount, String code, String message);
    void markSent(long taskId, int attemptCount, LocalDateTime sentAt);
    void recordDelivery(long taskId, int attemptNo, String providerCode,
                        NotificationDeliveryStatus status, String externalMessageId,
                        String errorCode, String errorMessage, long durationMs);

    boolean preferenceEnabled(long userId, String eventType, NotificationChannel channel);
    List<NotificationPreference> preferences(long userId);
    void upsertPreference(long userId, String eventType, NotificationChannel channel, boolean enabled);
    Optional<Long> findUserIdByOpenId(String openId);

    NotificationTaskPage queryTasks(NotificationTaskStatus status, NotificationChannel channel,
                                    String eventType, int page, int size);
    List<NotificationDelivery> deliveries(long taskId);
    int requeueWaitingConfiguration(NotificationChannel channel, String eventType,
                                    String templateCode, int limit);
}
