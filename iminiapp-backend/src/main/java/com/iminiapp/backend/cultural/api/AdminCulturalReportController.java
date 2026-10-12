package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalReportModels.*;
import com.iminiapp.backend.cultural.application.CulturalReportService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/cultural-store/reports")
public class AdminCulturalReportController {
    private final CulturalReportService service;
    public AdminCulturalReportController(CulturalReportService service) { this.service = service; }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardResponse> dashboard(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate, HttpServletRequest request) {
        return ApiResponse.success(service.dashboard(startDate,endDate), TraceIds.from(request));
    }
    @GetMapping("/overview")
    public ApiResponse<OverviewResponse> overview(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate, HttpServletRequest request) {
        return ApiResponse.success(service.overview(startDate,endDate), TraceIds.from(request));
    }
    @GetMapping("/sales-trend")
    public ApiResponse<List<SalesTrendItem>> salesTrend(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate, HttpServletRequest request) {
        return ApiResponse.success(service.salesTrend(startDate,endDate), TraceIds.from(request));
    }
    @GetMapping("/top-products")
    public ApiResponse<List<ProductRankingItem>> topProducts(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue="10") int limit, HttpServletRequest request) {
        return ApiResponse.success(service.topProducts(startDate,endDate,limit), TraceIds.from(request));
    }
    @GetMapping("/after-sales")
    public ApiResponse<AfterSaleSummary> afterSales(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate, HttpServletRequest request) {
        return ApiResponse.success(service.afterSaleSummary(startDate,endDate), TraceIds.from(request));
    }
    @GetMapping("/inventory")
    public ApiResponse<PageResponse<InventoryReportItem>> inventory(@RequestParam(required=false) String keyword,
            @RequestParam(required=false) Boolean lowStockOnly, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, HttpServletRequest request) {
        return ApiResponse.success(service.inventory(keyword,lowStockOnly,page,pageSize), TraceIds.from(request));
    }
    @GetMapping("/stock-movements")
    public ApiResponse<List<StockMovementItem>> stockMovements(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate, HttpServletRequest request) {
        return ApiResponse.success(service.stockMovements(startDate,endDate), TraceIds.from(request));
    }
    @GetMapping(value="/sales-export", produces="text/csv")
    public ResponseEntity<byte[]> salesExport(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=cultural-sales-report.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(service.salesCsv(startDate,endDate));
    }
}
