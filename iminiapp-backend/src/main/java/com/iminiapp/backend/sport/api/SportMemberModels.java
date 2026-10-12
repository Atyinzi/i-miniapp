package com.iminiapp.backend.sport.api;

import java.time.LocalDateTime;
import java.util.List;

public final class SportMemberModels {
    private SportMemberModels() {}

    public record MemberResponse(long id, String memberNo, long userId, String levelCode,
                                 String levelName, int sportDiscountBp, List<String> benefits,
                                 String status, long balanceCent, int points, int growthValue,
                                 LocalDateTime registeredAt) {}
    public record RechargeRequest(Long amountCent, String remark) {}
    public record WalletTransactionResponse(long id, String transactionNo, String transactionType,
                                            long amountCent, long balanceBeforeCent, long balanceAfterCent,
                                            Long orderId, String referenceNo, String remark,
                                            LocalDateTime createdAt) {}
    public record MemberLevelUpsertRequest(String levelCode, String levelName, Integer sportDiscountBp,
                                           Integer minGrowthValue, List<String> benefits, String status) {}
    public record MemberLevelResponse(long id, String levelCode, String levelName, int sportDiscountBp,
                                      int minGrowthValue, List<String> benefits, String status,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record AssignLevelRequest(String levelCode) {}
    public record AdminMemberResponse(long id, String memberNo, long userId, String nickname,
                                      String mobile, String levelCode, String levelName,
                                      int sportDiscountBp, String status, long balanceCent,
                                      int points, int growthValue, LocalDateTime registeredAt) {}
}
