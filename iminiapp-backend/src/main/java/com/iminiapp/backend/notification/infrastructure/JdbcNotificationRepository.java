package com.iminiapp.backend.notification.infrastructure;

import com.iminiapp.backend.notification.application.*;
import com.iminiapp.backend.notification.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {
    private final JdbcTemplate jdbc;

    public JdbcNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insertTask(long userId, String businessType, String businessRef, String eventType,
                           NotificationChannel channel, String templateCode, String deduplicationKey,
                           String payloadJson, NotificationTaskStatus status, int maxAttempts) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_notification_task(
                        user_id,business_type,business_ref,event_type,channel,template_code,
                        deduplication_key,payload,status,max_attempts,next_attempt_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, businessType);
            statement.setString(3, businessRef);
            statement.setString(4, eventType);
            statement.setString(5, channel.name());
            statement.setString(6, templateCode);
            statement.setString(7, deduplicationKey);
            statement.setString(8, payloadJson);
            statement.setString(9, status.name());
            statement.setInt(10, maxAttempts);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("通知任务主键生成失败");
        return key.longValue();
    }

    @Override public Optional<NotificationTask> findByDeduplicationKey(String key) {
        return jdbc.query(taskSelect() + " WHERE deduplication_key=?", taskMapper(), key).stream().findFirst();
    }

    @Override public Optional<NotificationTask> findTask(long id) {
        return jdbc.query(taskSelect() + " WHERE id=?", taskMapper(), id).stream().findFirst();
    }

    @Override public List<Long> findDueTaskIds(LocalDateTime now, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM bf_notification_task
                WHERE status IN ('PENDING','RETRY') AND next_attempt_at<=?
                ORDER BY next_attempt_at,id LIMIT ?
                """, Long.class, Timestamp.valueOf(now), limit);
    }

    @Override public boolean claim(long taskId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE bf_notification_task SET status='PROCESSING',updated_at=?
                WHERE id=? AND status IN ('PENDING','RETRY') AND next_attempt_at<=?
                """, Timestamp.valueOf(now), taskId, Timestamp.valueOf(now)) == 1;
    }

    @Override public int recoverStaleProcessing(LocalDateTime staleBefore, LocalDateTime retryAt) {
        return jdbc.update("""
                UPDATE bf_notification_task
                SET status='RETRY',next_attempt_at=?,last_error_code='WORKER_INTERRUPTED',
                    last_error_message='上次投递未完成，已安全恢复为待重试'
                WHERE status='PROCESSING' AND updated_at<?
                """, Timestamp.valueOf(retryAt), Timestamp.valueOf(staleBefore));
    }

    @Override public int nextDeliveryAttempt(long taskId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no),0)+1 FROM bf_notification_delivery WHERE task_id=?",
                Integer.class, taskId);
        return value == null ? 1 : value;
    }

    @Override public int countRecentSent(long userId, NotificationChannel channel, LocalDateTime since) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_notification_delivery d
                JOIN bf_notification_task t ON t.id=d.task_id
                WHERE t.user_id=? AND t.channel=? AND d.delivery_status='SENT' AND d.attempted_at>=?
                """, Integer.class, userId, channel.name(), Timestamp.valueOf(since));
        return value == null ? 0 : value;
    }

    @Override public Optional<String> findRecipient(long userId, NotificationChannel channel) {
        String column = channel == NotificationChannel.SMS ? "mobile" : "wechat_openid";
        return jdbc.query("SELECT " + column + " FROM bf_user WHERE id=? AND status='ACTIVE'",
                (rs, row) -> rs.getString(1), userId).stream()
                .filter(value -> value != null && !value.isBlank()).findFirst();
    }

    @Override public void markWaitingConfiguration(long taskId, String code, String message) {
        jdbc.update("""
                UPDATE bf_notification_task
                SET status='WAITING_CONFIGURATION',last_error_code=?,last_error_message=?
                WHERE id=? AND status='PROCESSING'
                """, code, message, taskId);
    }

    @Override public void markRetry(long taskId, int attemptCount, LocalDateTime nextAttemptAt, String code, String message) {
        jdbc.update("""
                UPDATE bf_notification_task
                SET status='RETRY',attempt_count=?,next_attempt_at=?,last_error_code=?,last_error_message=?
                WHERE id=? AND status='PROCESSING'
                """, attemptCount, Timestamp.valueOf(nextAttemptAt), code, message, taskId);
    }

    @Override public void markFailed(long taskId, int attemptCount, String code, String message) {
        jdbc.update("""
                UPDATE bf_notification_task
                SET status='FAILED',attempt_count=?,last_error_code=?,last_error_message=?
                WHERE id=? AND status='PROCESSING'
                """, attemptCount, code, message, taskId);
    }

    @Override public void markSent(long taskId, int attemptCount, LocalDateTime sentAt) {
        jdbc.update("""
                UPDATE bf_notification_task
                SET status='SENT',attempt_count=?,sent_at=?,last_error_code=NULL,last_error_message=NULL
                WHERE id=? AND status='PROCESSING'
                """, attemptCount, Timestamp.valueOf(sentAt), taskId);
    }

    @Override public void recordDelivery(long taskId, int attemptNo, String providerCode,
                                         NotificationDeliveryStatus status, String externalMessageId,
                                         String errorCode, String errorMessage, long durationMs) {
        jdbc.update("""
                INSERT INTO bf_notification_delivery(
                    task_id,attempt_no,provider_code,delivery_status,external_message_id,
                    error_code,error_message,duration_ms)
                VALUES (?,?,?,?,?,?,?,?)
                """, taskId, attemptNo, providerCode, status.name(), externalMessageId,
                errorCode, errorMessage, durationMs);
    }

    @Override public boolean preferenceEnabled(long userId, String eventType, NotificationChannel channel) {
        List<Boolean> exact = jdbc.query("""
                SELECT enabled FROM bf_notification_preference
                WHERE user_id=? AND event_type=? AND channel=?
                """, (rs, row) -> rs.getBoolean(1), userId, eventType, channel.name());
        if (!exact.isEmpty()) return exact.get(0);
        List<Boolean> wildcard = jdbc.query("""
                SELECT enabled FROM bf_notification_preference
                WHERE user_id=? AND event_type='*' AND channel=?
                """, (rs, row) -> rs.getBoolean(1), userId, channel.name());
        return wildcard.isEmpty() || wildcard.get(0);
    }

    @Override public List<NotificationPreference> preferences(long userId) {
        return jdbc.query("""
                SELECT event_type,channel,enabled FROM bf_notification_preference
                WHERE user_id=? ORDER BY event_type,channel
                """, (rs, row) -> new NotificationPreference(
                rs.getString(1), NotificationChannel.valueOf(rs.getString(2)), rs.getBoolean(3)), userId);
    }

    @Override public void upsertPreference(long userId, String eventType, NotificationChannel channel, boolean enabled) {
        jdbc.update("""
                INSERT INTO bf_notification_preference(user_id,event_type,channel,enabled)
                VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)
                """, userId, eventType, channel.name(), enabled);
    }

    @Override public Optional<Long> findUserIdByOpenId(String openId) {
        return jdbc.query("SELECT id FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'",
                (rs, row) -> rs.getLong(1), openId).stream().findFirst();
    }

    @Override public NotificationTaskPage queryTasks(NotificationTaskStatus status, NotificationChannel channel,
                                                     String eventType, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) { where.append(" AND status=?"); args.add(status.name()); }
        if (channel != null) { where.append(" AND channel=?"); args.add(channel.name()); }
        if (eventType != null && !eventType.isBlank()) { where.append(" AND event_type=?"); args.add(eventType); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM bf_notification_task" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<NotificationTask> items = jdbc.query(taskSelect() + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                taskMapper(), pageArgs.toArray());
        return new NotificationTaskPage(items, total == null ? 0 : total, page, size);
    }

    @Override public List<NotificationDelivery> deliveries(long taskId) {
        return jdbc.query("""
                SELECT id,task_id,attempt_no,provider_code,delivery_status,external_message_id,
                       error_code,error_message,duration_ms,attempted_at
                FROM bf_notification_delivery WHERE task_id=? ORDER BY attempt_no
                """, (rs, row) -> new NotificationDelivery(
                rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                NotificationDeliveryStatus.valueOf(rs.getString(5)), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getLong(9), rs.getTimestamp(10).toLocalDateTime()), taskId);
    }

    @Override public int requeueWaitingConfiguration(NotificationChannel channel, String eventType,
                                                      String templateCode, int limit) {
        return jdbc.update("""
                UPDATE bf_notification_task
                SET template_code=?,status='PENDING',next_attempt_at=CURRENT_TIMESTAMP,
                    last_error_code=NULL,last_error_message=NULL
                WHERE channel=? AND event_type=? AND status='WAITING_CONFIGURATION'
                ORDER BY id LIMIT ?
                """, templateCode, channel.name(), eventType, limit);
    }

    private String taskSelect() {
        return """
                SELECT id,user_id,business_type,business_ref,event_type,channel,template_code,payload,
                       status,attempt_count,max_attempts,next_attempt_at,last_error_code,last_error_message,
                       sent_at,created_at,updated_at
                FROM bf_notification_task
                """;
    }

    private RowMapper<NotificationTask> taskMapper() {
        return (rs, row) -> new NotificationTask(
                rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                NotificationChannel.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8),
                NotificationTaskStatus.valueOf(rs.getString(9)), rs.getInt(10), rs.getInt(11),
                local(rs.getTimestamp(12)), rs.getString(13), rs.getString(14), local(rs.getTimestamp(15)),
                local(rs.getTimestamp(16)), local(rs.getTimestamp(17)));
    }

    private LocalDateTime local(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
}
