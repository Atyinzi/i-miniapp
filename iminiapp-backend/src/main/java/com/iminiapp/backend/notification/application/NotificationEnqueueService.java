package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.*;
import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class NotificationEnqueueService {
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_.-]{0,63}$");
    private static final Pattern TEMPLATE_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            ".*(password|passwd|secret|token|credential|api.?key|openid|mobile|phone|id.?card).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            ".*((?<!\\d)1\\d{10}(?!\\d)|(?<!\\d)\\d{17}[0-9Xx](?!\\d)).*");

    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;

    public NotificationEnqueueService(NotificationRepository repository,
                                      NotificationProperties properties,
                                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<NotificationEnqueueResult> enqueueOrderEvent(OrderEventNotificationCommand command) {
        Validated value = validate(command);
        List<NotificationEnqueueResult> results = new ArrayList<>();
        for (NotificationChannel channel : value.channels()) {
            boolean enabled = repository.preferenceEnabled(value.userId(), value.eventType(), channel);
            NotificationTaskStatus status = !enabled
                    ? NotificationTaskStatus.SKIPPED_PREFERENCE
                    : value.templateCode() == null
                    ? NotificationTaskStatus.WAITING_CONFIGURATION
                    : NotificationTaskStatus.PENDING;
            String deduplicationKey = sha256(String.join("|",
                    "ORDER", Long.toString(value.userId()), value.orderNo(), value.eventType(),
                    channel.name(), value.idempotencyKey()));
            try {
                long taskId = repository.insertTask(value.userId(), "ORDER", value.orderNo(), value.eventType(),
                        channel, value.templateCode(), deduplicationKey, value.payloadJson(), status,
                        properties.getMaxAttempts());
                results.add(new NotificationEnqueueResult(taskId, channel, status, false));
            } catch (DuplicateKeyException duplicate) {
                NotificationTask existing = repository.findByDeduplicationKey(deduplicationKey)
                        .orElseThrow(() -> duplicate);
                results.add(new NotificationEnqueueResult(
                        existing.id(), existing.channel(), existing.status(), true));
            }
        }
        return List.copyOf(results);
    }

    private Validated validate(OrderEventNotificationCommand command) {
        if (command == null || command.userId() <= 0) throw invalid("通知用户不正确");
        String orderNo = required(command.orderNo(), 64, "订单号");
        String eventType = code(command.eventType(), "事件类型");
        String templateCode = nullableTemplateCode(command.templateCode());
        String idempotency = StringUtils.hasText(command.idempotencyKey())
                ? required(command.idempotencyKey(), 128, "通知幂等键") : eventType;
        if (command.channels() == null || command.channels().isEmpty()
                || command.channels().stream().anyMatch(Objects::isNull)) {
            throw invalid("至少选择一个有效通知渠道");
        }
        if (command.channels().size() > NotificationChannel.values().length) throw invalid("通知渠道重复或超限");
        Map<String, String> payload = command.templateData() == null
                ? Map.of() : command.templateData();
        if (payload.size() > 30) throw invalid("通知模板数据最多30项");
        Map<String, String> safe = new TreeMap<>();
        payload.forEach((key, rawValue) -> {
            String normalizedKey = required(key, 64, "模板数据键");
            if (!CODE.matcher(normalizedKey.toUpperCase(Locale.ROOT)).matches()) {
                throw invalid("模板数据键格式不正确");
            }
            if (SENSITIVE_KEY.matcher(normalizedKey).matches()) {
                throw invalid("通知任务不得包含手机号、OpenID、令牌或凭据等敏感字段");
            }
            String value = rawValue == null ? "" : rawValue.trim();
            if (value.length() > 500) throw invalid("单项模板数据最多500个字符");
            if (SENSITIVE_VALUE.matcher(value).matches()) {
                throw invalid("通知任务不得保存手机号或身份证号等明文敏感数据");
            }
            safe.put(normalizedKey, value);
        });
        try {
            return new Validated(command.userId(), orderNo, eventType, templateCode,
                    objectMapper.writeValueAsString(safe), Set.copyOf(command.channels()), idempotency);
        } catch (Exception exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "通知模板数据序列化失败");
        }
    }

    private String code(String value, String name) {
        String normalized = required(value, 64, name).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw invalid(name + "格式不正确");
        return normalized;
    }

    private String nullableTemplateCode(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = required(value, 64, "模板代码");
        if (!TEMPLATE_CODE.matcher(normalized).matches()) throw invalid("模板代码格式不正确");
        return normalized;
    }

    private String required(String value, int max, String name) {
        if (!StringUtils.hasText(value)) throw invalid(name + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid(name + "最多" + max + "个字符");
        return normalized;
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
    }

    private record Validated(long userId, String orderNo, String eventType, String templateCode,
                             String payloadJson, Set<NotificationChannel> channels,
                             String idempotencyKey) {
    }
}
