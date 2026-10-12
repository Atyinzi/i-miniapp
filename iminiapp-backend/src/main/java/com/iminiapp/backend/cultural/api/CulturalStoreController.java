package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.cultural.application.CulturalStoreService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/cultural-store")
public class CulturalStoreController {
    private final CulturalStoreService service;

    public CulturalStoreController(CulturalStoreService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryResponse>> categories(HttpServletRequest request) {
        return ApiResponse.success(service.categories(true), TraceIds.from(request));
    }

    @GetMapping("/products")
    public ApiResponse<PageResponse<ProductListItem>> products(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(service.publicProducts(keyword, categoryId, page, pageSize),
                TraceIds.from(request));
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<ProductDetail> productDetail(@PathVariable long productId, HttpServletRequest request) {
        return ApiResponse.success(service.publicProductDetail(productId), TraceIds.from(request));
    }
}
