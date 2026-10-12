package com.iminiapp.backend.notification.api;

import com.iminiapp.backend.notification.infrastructure.NotificationProperties;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
public class NotificationConfigurationController {
    private final NotificationProperties properties;

    public NotificationConfigurationController(NotificationProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/v1/app/notification-config")
    public ApiResponse<NotificationConfigurationResponse> configuration(HttpServletRequest request) {
        boolean deliveryReady = properties.isSendingEnabled()
                && properties.isWorkerEnabled()
                && properties.isWechatEnabled();
        if (!deliveryReady) {
            return ApiResponse.success(new NotificationConfigurationResponse(false, List.of()),
                    TraceIds.from(request));
        }
        List<NotificationConfigurationResponse.WechatTemplate> templates = properties.getWechatTemplates().keySet()
                .stream().filter(key -> key != null && !key.isBlank())
                .map(key -> key.trim().toUpperCase(Locale.ROOT)).distinct()
                .map(eventType -> new NotificationConfigurationResponse.WechatTemplate(
                        eventType, properties.templateFor(eventType, true)))
                .filter(template -> template.templateId() != null
                        && template.templateId().matches("[A-Za-z0-9_-]{1,64}"))
                .sorted(Comparator.comparing(NotificationConfigurationResponse.WechatTemplate::eventType))
                .toList();
        return ApiResponse.success(new NotificationConfigurationResponse(!templates.isEmpty(), templates),
                TraceIds.from(request));
    }
}
