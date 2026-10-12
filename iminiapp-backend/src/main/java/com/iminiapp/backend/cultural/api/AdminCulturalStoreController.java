package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.CulturalStoreService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/cultural-store")
public class AdminCulturalStoreController {
    private final CulturalStoreService service;

    public AdminCulturalStoreController(CulturalStoreService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryResponse>> categories(HttpServletRequest request) {
        return ApiResponse.success(service.categories(false), TraceIds.from(request));
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryResponse> createCategory(@RequestBody CategoryUpsertRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("文创商品分类创建成功",
                service.createCategory(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<CategoryResponse> updateCategory(@PathVariable long categoryId,
                                                        @RequestBody CategoryUpsertRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("文创商品分类更新成功",
                service.updateCategory(CurrentPrincipal.from(request), categoryId, body), TraceIds.from(request));
    }

    @GetMapping("/products")
    public ApiResponse<PageResponse<ProductListItem>> products(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(service.adminProducts(keyword, categoryId, status, page, pageSize),
                TraceIds.from(request));
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<ProductDetail> productDetail(@PathVariable long productId, HttpServletRequest request) {
        return ApiResponse.success(service.adminProductDetail(productId), TraceIds.from(request));
    }

    @PostMapping("/products")
    public ApiResponse<ProductDetail> createProduct(@RequestBody ProductUpsertRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.success("文创商品创建成功",
                service.createProduct(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PutMapping("/products/{productId}")
    public ApiResponse<ProductDetail> updateProduct(@PathVariable long productId,
                                                    @RequestBody ProductUpsertRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.success("文创商品更新成功",
                service.updateProduct(CurrentPrincipal.from(request), productId, body), TraceIds.from(request));
    }

    @PatchMapping("/products/{productId}/status")
    public ApiResponse<ProductDetail> updateProductStatus(@PathVariable long productId,
                                                          @RequestBody ProductStatusRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.success("文创商品状态更新成功",
                service.updateProductStatus(CurrentPrincipal.from(request), productId, body.status()),
                TraceIds.from(request));
    }

    @PostMapping("/products/{productId}/skus")
    public ApiResponse<SkuResponse> createSku(@PathVariable long productId, @RequestBody SkuCreateRequest body,
                                             HttpServletRequest request) {
        return ApiResponse.success("文创商品SKU创建成功",
                service.createSku(CurrentPrincipal.from(request), productId, body), TraceIds.from(request));
    }

    @PutMapping("/skus/{skuId}")
    public ApiResponse<SkuResponse> updateSku(@PathVariable long skuId, @RequestBody SkuUpdateRequest body,
                                             HttpServletRequest request) {
        return ApiResponse.success("文创商品SKU更新成功",
                service.updateSku(CurrentPrincipal.from(request), skuId, body), TraceIds.from(request));
    }

    @GetMapping("/stocks")
    public ApiResponse<PageResponse<StockResponse>> stocks(
            @RequestParam(required = false) Boolean lowStock,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(service.stocks(lowStock, keyword, page, pageSize), TraceIds.from(request));
    }

    @PostMapping("/stocks/{skuId}/adjustments")
    public ApiResponse<StockResponse> adjustStock(
            @PathVariable long skuId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StockAdjustmentRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("库存调整成功", service.adjustStock(
                CurrentPrincipal.from(request), skuId, idempotencyKey, body), TraceIds.from(request));
    }

    @PostMapping("/stocks/batch-adjustments")
    public ApiResponse<List<StockResponse>> batchAdjustStock(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody BatchStockRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("批量库存调整成功", service.batchAdjustStock(
                CurrentPrincipal.from(request), idempotencyKey, body), TraceIds.from(request));
    }

    @GetMapping("/stock-logs")
    public ApiResponse<PageResponse<StockLogResponse>> stockLogs(
            @RequestParam(required = false) Long skuId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(service.stockLogs(skuId, page, pageSize), TraceIds.from(request));
    }
}
