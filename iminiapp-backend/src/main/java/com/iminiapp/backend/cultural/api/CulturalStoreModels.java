package com.iminiapp.backend.cultural.api;

import java.time.LocalDateTime;
import java.util.List;

public final class CulturalStoreModels {
    private CulturalStoreModels() {}

    public record CategoryUpsertRequest(String categoryCode, String name, Long parentId, Integer sortOrder,
                                        String status) {}
    public record CategoryResponse(long id, String categoryCode, String name, Long parentId, int sortOrder,
                                   String status, long productCount) {}

    public record ImageInput(String imageUrl, String imageType, Integer sortOrder) {}
    public record SkuCreateRequest(String skuCode, String name, Long priceCent, String status, String attributes,
                                   Integer initialStock, Integer warningThreshold) {}
    public record SkuUpdateRequest(String name, Long priceCent, String status, String attributes,
                                   Integer warningThreshold) {}
    public record ProductUpsertRequest(String productCode, String name, Long categoryId, String description,
                                       String coverUrl, String detailHtml, Long marketPriceCent, Integer weightGram,
                                       String status, List<ImageInput> images, List<SkuCreateRequest> skus) {}
    public record ProductStatusRequest(String status) {}
    public record ProductListItem(long id, String productCode, String name, String description,
                                  long categoryId, String categoryName,
                                  String coverUrl, String status, Long minimumPriceCent, long totalAvailable,
                                  long salesCount, int skuCount, LocalDateTime updatedAt) {}
    public record ImageResponse(long id, String imageUrl, String imageType, int sortOrder) {}
    public record SkuResponse(long skuId, String skuCode, String name, long priceCent, String status,
                              String attributes, int onHandQuantity, int lockedQuantity, int availableQuantity,
                              int warningThreshold, boolean lowStock) {}
    public record ProductDetail(long id, String productCode, String name, long categoryId, String categoryName,
                                String description, String coverUrl, String detailHtml, Long marketPriceCent,
                                Integer weightGram, long salesCount, String status, List<ImageResponse> images,
                                List<SkuResponse> skus) {}

    public record StockAdjustmentRequest(Integer quantityDelta, String changeType, String referenceNo,
                                         String remark) {}
    public record BatchStockItem(Long skuId, Integer quantityDelta, String changeType, String referenceNo,
                                 String remark) {}
    public record BatchStockRequest(List<BatchStockItem> items) {}
    public record StockResponse(long skuId, String skuCode, String productName, String skuName,
                                int onHandQuantity, int lockedQuantity, int availableQuantity,
                                int warningThreshold, boolean lowStock, int version, LocalDateTime updatedAt) {}
    public record StockLogResponse(long id, long skuId, String skuCode, String changeType, int quantityDelta,
                                   int onHandAfter, int availableAfter, String referenceNo, String remark,
                                   Long operatorId, LocalDateTime createdAt) {}
}
