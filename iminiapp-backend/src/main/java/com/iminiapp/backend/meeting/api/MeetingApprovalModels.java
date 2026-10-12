package com.iminiapp.backend.meeting.api;

import java.time.LocalDateTime;
import java.util.List;

public final class MeetingApprovalModels {
    private MeetingApprovalModels() {}

    public record RuleStepRequest(long approverUserId, String stepName) {}
    public record ReplaceRulesRequest(List<RuleStepRequest> steps) {}
    public record RuleResponse(long id, int workflowVersion, int stepNo, String stepName,
                               long approverUserId, String approverName, String status) {}
    public record DecisionRequest(String comment) {}
    public record TaskResponse(long taskId, long instanceId, long bookingId, String bookingNo,
                               String meetingSubject, int stepNo, String stepName,
                               long approverUserId, String approverName, String status,
                               String comment, LocalDateTime handledAt, LocalDateTime createdAt) {}
    public record ProgressResponse(long instanceId, long bookingId, String bookingNo,
                                   String title, String status, int currentStep,
                                   int workflowVersion, long applicantUserId,
                                   LocalDateTime submittedAt, LocalDateTime completedAt,
                                   List<TaskResponse> tasks) {}
}
