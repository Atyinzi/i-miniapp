package com.iminiapp.backend.cultural;

import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.CulturalStoreService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.task.scheduling.enabled=false"
        })
@Transactional
class CulturalStoreServiceIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "cultural-store-admin", "文创商城管理员", "ADMIN", List.of("ROLE_ADMIN"));

    @Autowired private CulturalStoreService service;

    @Test
    void 商品上架后可公开查询且库存出入库幂等并触发预警() {
        CategoryResponse category = service.createCategory(ADMIN,
                new CategoryUpsertRequest("IT_CULTURAL_GIFT", "测试文创礼品", null, 10, "ACTIVE"));
        ProductDetail product = service.createProduct(ADMIN, new ProductUpsertRequest(
                "IT_CULTURAL_PRODUCT", "i某小程序测试文创模型", category.id(), "测试商品", "/test-cover.jpg",
                "<p>文创商品详情</p>", 12900L, 500, "DRAFT",
                List.of(new ImageInput("/test-gallery.jpg", "GALLERY", 1)),
                List.of(new SkuCreateRequest("IT_CULTURAL_SKU", "标准版", 9900L, "ACTIVE",
                        "{\"edition\":\"standard\"}", 12, 5))));

        assertEquals(0, service.publicProducts(null, null, 1, 20).items().stream()
                .filter(item -> item.id() == product.id()).count());
        service.updateProductStatus(ADMIN, product.id(), "ACTIVE");
        ProductListItem listed = service.publicProducts("i某小程序测试", category.id(), 1, 20).items().stream()
                .filter(item -> item.id() == product.id()).findFirst().orElseThrow();
        assertEquals("测试商品", listed.description());
        assertEquals(9900L, listed.minimumPriceCent());
        assertEquals(12, listed.totalAvailable());

        long skuId = product.skus().get(0).skuId();
        StockResponse afterOutbound = service.adjustStock(ADMIN, skuId, "it-cultural-outbound",
                new StockAdjustmentRequest(-8, "OUTBOUND", "OUT-001", "展厅领用"));
        StockResponse duplicate = service.adjustStock(ADMIN, skuId, "it-cultural-outbound",
                new StockAdjustmentRequest(-8, "OUTBOUND", "OUT-001", "重复请求"));

        assertEquals(4, afterOutbound.availableQuantity());
        assertTrue(afterOutbound.lowStock());
        assertEquals(4, duplicate.availableQuantity());
        assertEquals(1, service.stocks(true, "IT_CULTURAL_SKU", 1, 20).total());
        assertThrows(BusinessException.class, () -> service.adjustStock(ADMIN, skuId, "it-cultural-overdraw",
                new StockAdjustmentRequest(-5, "OUTBOUND", "OUT-002", "超量出库")));
    }

    @Test
    void 批量入库可原子更新多个SKU并记录流水() {
        CategoryResponse category = service.createCategory(ADMIN,
                new CategoryUpsertRequest("IT_CULTURAL_BATCH", "批量库存测试", null, 20, "ACTIVE"));
        ProductDetail product = service.createProduct(ADMIN, new ProductUpsertRequest(
                "IT_CULTURAL_BATCH_PRODUCT", "批量库存商品", category.id(), null, null, null,
                null, null, "DRAFT", List.of(), List.of(
                new SkuCreateRequest("IT_CULTURAL_BATCH_A", "A款", 1000L, "ACTIVE", "{}", 0, 2),
                new SkuCreateRequest("IT_CULTURAL_BATCH_B", "B款", 2000L, "ACTIVE", "{}", 0, 2))));

        List<StockResponse> result = service.batchAdjustStock(ADMIN, "it-cultural-batch", new BatchStockRequest(
                List.of(
                        new BatchStockItem(product.skus().get(0).skuId(), 20, "INBOUND", "IN-001", "首批入库"),
                        new BatchStockItem(product.skus().get(1).skuId(), 30, "INBOUND", "IN-001", "首批入库"))));

        assertEquals(List.of(20, 30), result.stream().map(StockResponse::availableQuantity).toList());
        assertEquals(2, service.stockLogs(null, 1, 20).items().stream()
                .filter(item -> "IN-001".equals(item.referenceNo())).count());
    }
}
