package com.iminiapp.backend.cultural.api;

import java.time.LocalDateTime;
import java.util.List;

public final class CulturalAfterSaleModels {
    private CulturalAfterSaleModels() {}
    public record ApplyRequest(String type, String reason, List<String> evidenceUrls) {}
    public record ApproveRequest(String comment, String returnAddress) {}
    public record ReviewRequest(String comment) {}
    public record ReturnShipmentRequest(String carrierCode, String trackingNo, String remark) {}
    public record ReturnShipmentResponse(String carrierCode, String carrierName, String trackingNo,
                                         LocalDateTime shippedAt, LocalDateTime receivedAt, String remark) {}
    public record StatusLogResponse(long id, String action, String fromStatus, String toStatus,
                                    Long operatorId, String note, LocalDateTime createdAt) {}
    public record AfterSaleResponse(long id, String afterSaleNo, long orderId, String orderNo,
                                    String type, String reason, List<String> evidenceUrls, String status,
                                    long refundAmountCent, Long refundId, String refundStatus,
                                    Long requestedBy, Long reviewedBy, String reviewerName,
                                    String reviewComment, String returnAddress,
                                    ReturnShipmentResponse returnShipment, List<StatusLogResponse> logs,
                                    LocalDateTime createdAt, LocalDateTime reviewedAt,
                                    LocalDateTime completedAt, LocalDateTime cancelledAt) {}
}
