package com.iminiapp.backend.meeting.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.meeting.api.MeetingApprovalModels.*;
import com.iminiapp.backend.meeting.application.MeetingApprovalService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/meetings/approvals")
public class AdminMeetingApprovalController {
    private final MeetingApprovalService service;

    public AdminMeetingApprovalController(MeetingApprovalService service) { this.service = service; }

    @GetMapping("/rules")
    public ApiResponse<List<RuleResponse>> rules(HttpServletRequest request) {
        return ApiResponse.success(service.rules(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @PutMapping("/rules")
    public ApiResponse<List<RuleResponse>> replaceRules(@RequestBody ReplaceRulesRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("会议审批流程配置成功",
                service.replaceRules(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskResponse>> myTasks(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            HttpServletRequest request) {
        return ApiResponse.success(service.myTasks(CurrentPrincipal.from(request), status), TraceIds.from(request));
    }

    @GetMapping("/bookings/{bookingId}")
    public ApiResponse<ProgressResponse> progress(@PathVariable long bookingId, HttpServletRequest request) {
        return ApiResponse.success(service.progress(CurrentPrincipal.from(request), bookingId), TraceIds.from(request));
    }

    @PostMapping("/tasks/{taskId}/approve")
    public ApiResponse<ProgressResponse> approve(@PathVariable long taskId,
                                                 @RequestBody(required = false) DecisionRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.success("审批通过操作成功",
                service.decide(CurrentPrincipal.from(request), taskId, true, body), TraceIds.from(request));
    }

    @PostMapping("/tasks/{taskId}/reject")
    public ApiResponse<ProgressResponse> reject(@PathVariable long taskId,
                                                @RequestBody(required = false) DecisionRequest body,
                                                HttpServletRequest request) {
        return ApiResponse.success("审批驳回操作成功",
                service.decide(CurrentPrincipal.from(request), taskId, false, body), TraceIds.from(request));
    }
}
