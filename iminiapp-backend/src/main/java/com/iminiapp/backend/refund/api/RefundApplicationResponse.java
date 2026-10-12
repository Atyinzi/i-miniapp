package com.iminiapp.backend.refund.api;

import java.time.LocalDateTime;

public record RefundApplicationResponse(
        long id, long orderId, String orderNo, String refundNo, String channelRefundId,
        long amountCent, String currency, String reason, String status,
        String refundKind, boolean automatic,
        String dispatchStatus, int dispatchAttemptCount, String dispatchLastError,
        Long requestedBy, Long reviewedBy, String reviewerName, String reviewComment,
        String failureReason, LocalDateTime requestedAt, LocalDateTime reviewedAt, LocalDateTime refundedAt) {
}
