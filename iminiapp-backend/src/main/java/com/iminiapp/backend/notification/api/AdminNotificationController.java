package com.iminiapp.backend.notification.api;

import com.iminiapp.backend.notification.application.*;
import com.iminiapp.backend.notification.domain.*;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {
    private final NotificationAdminQueryService service;

    public AdminNotificationController(NotificationAdminQueryService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public ApiResponse<NotificationTaskPage> tasks(
            @RequestParam(required = false) NotificationTaskStatus status,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        return ApiResponse.success(service.tasks(status, channel, eventType, page, size), TraceIds.from(request));
    }

    @GetMapping("/tasks/{taskId}/deliveries")
    public ApiResponse<List<NotificationDelivery>> deliveries(
            @PathVariable long taskId, HttpServletRequest request) {
        return ApiResponse.success(service.deliveries(taskId), TraceIds.from(request));
    }

    @PostMapping("/tasks/requeue-configured")
    public ApiResponse<Integer> requeueConfigured(
            @RequestParam NotificationChannel channel,
            @RequestParam String eventType,
            @RequestParam String templateCode,
            @RequestParam(defaultValue = "500") int limit,
            HttpServletRequest request) {
        return ApiResponse.success("等待配置的任务已重新排队",
                service.requeueConfigured(channel, eventType, templateCode, limit), TraceIds.from(request));
    }
}
