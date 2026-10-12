package com.iminiapp.backend.cultural.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class CulturalReportModels {
    private CulturalReportModels() {}
    public record OverviewResponse(LocalDate startDate, LocalDate endDate, long createdOrderCount,
                                   long paidOrderCount, long completedOrderCount, long soldQuantity,
                                   long grossSalesCent, long discountCent, long refundOrderCount,
                                   long refundAmountCent, long netSalesCent, BigDecimal refundRate,
                                   long afterSaleApplicationCount, long pendingAfterSaleCount,
                                   long pendingShipmentCount, long lowStockSkuCount,
                                   long availableStockQuantity, long availableStockValueCent) {}
    public record SalesTrendItem(LocalDate date, long paidOrderCount, long soldQuantity,
                                 long grossSalesCent, long refundAmountCent, long netSalesCent) {}
    public record ProductRankingItem(long productId, String productCode, String productName,
                                     long skuCount, long soldQuantity, long salesAmountCent,
                                     long refundedOrderCount) {}
    public record AfterSaleSummary(long totalApplications, long refundOnlyCount, long returnRefundCount,
                                   long pendingReviewCount, long waitingReturnCount, long returnedCount,
                                   long refundingCount, long completedCount, long rejectedCount,
                                   long cancelledCount, long completedRefundAmountCent) {}
    public record InventoryReportItem(long skuId, String skuCode, String productName, String skuName,
                                      long priceCent, int onHandQuantity, int lockedQuantity,
                                      int availableQuantity, int warningThreshold, boolean lowStock,
                                      long availableValueCent, LocalDateTime updatedAt) {}
    public record StockMovementItem(LocalDate date, long inboundQuantity, long outboundQuantity,
                                    long saleQuantity, long returnQuantity, long adjustmentQuantity,
                                    long netChangeQuantity) {}
    public record DashboardResponse(OverviewResponse overview, List<SalesTrendItem> salesTrend,
                                    List<ProductRankingItem> topProducts,
                                    AfterSaleSummary afterSaleSummary) {}
}
