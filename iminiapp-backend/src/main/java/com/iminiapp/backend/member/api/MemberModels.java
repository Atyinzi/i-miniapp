package com.iminiapp.backend.member.api;

import java.time.LocalDateTime;
import java.util.List;

public final class MemberModels {
    private MemberModels() {}

    public record PointOperationRequest(String clientRequestId, String action, String orderId,
                                        Long paidAmountFen, String description) {}
    public record RechargeRequest(Long amountFen, String clientRequestId) {}
    public record PendingPointOrder(String orderId, long amountFen, int points, String description,
                                    LocalDateTime registeredAt) {}
    public record PointRecord(String orderId, String relatedOrderId, int points, String description,
                              LocalDateTime createdAt) {}
    public record MemberProfileResponse(
            String nickname, String avatarText, String avatarUrl, String memberNo,
            int points, int growthPoints, long balanceFen, int coupons,
            String levelCode, String level, String levelBadge,
            double discountRate, String discountLabel, String validUntil,
            List<String> rewardedOrderIds, List<PendingPointOrder> pendingPointOrders,
            List<PointRecord> pointRecords) {}
}
