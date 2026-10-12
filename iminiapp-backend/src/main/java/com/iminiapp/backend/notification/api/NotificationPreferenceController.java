package com.iminiapp.backend.notification.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.notification.application.NotificationPreferenceService;
import com.iminiapp.backend.notification.domain.NotificationPreference;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/notification-preferences")
public class NotificationPreferenceController {
    private final NotificationPreferenceService service;

    public NotificationPreferenceController(NotificationPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<NotificationPreference>> preferences(HttpServletRequest request) {
        return ApiResponse.success(service.preferences(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @PutMapping
    public ApiResponse<List<NotificationPreference>> update(
            @RequestBody NotificationApiModels.PreferenceUpdateRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(service.update(CurrentPrincipal.from(request),
                body == null ? null : body.preferences()), TraceIds.from(request));
    }
}
