package com.iminiapp.backend.sport.api;

import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.sport.api.SportReportModels.*;
import com.iminiapp.backend.sport.application.SportReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/sports/reports")
public class AdminSportReportController {
    private final SportReportService service;
    public AdminSportReportController(SportReportService service){this.service=service;}

    @GetMapping("/overview")
    public ApiResponse<SportReportOverviewResponse> overview(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required=false) Long venueId,HttpServletRequest request){
        return ApiResponse.success(service.overview(startDate,endDate,venueId),TraceIds.from(request));
    }
    @GetMapping("/utilization")
    public ApiResponse<List<SportUtilizationResponse>> utilization(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required=false) Long venueId,@RequestParam(defaultValue="COURT") String dimension,
            HttpServletRequest request){
        return ApiResponse.success(service.utilization(startDate,endDate,venueId,dimension),TraceIds.from(request));
    }
    @GetMapping("/usage-details")
    public ApiResponse<PageResponse<SportUsageDetailResponse>> usageDetails(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required=false) Long venueId,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            HttpServletRequest request){
        return ApiResponse.success(service.usageDetails(startDate,endDate,venueId,status,page,pageSize),TraceIds.from(request));
    }
    @GetMapping(value="/usage-details.csv",produces="text/csv")
    public ResponseEntity<byte[]> exportUsage(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required=false) Long venueId,@RequestParam(required=false) String status){
        String name="sport-usage-"+(startDate==null?"default":startDate)+"-"+(endDate==null?LocalDate.now():endDate)+".csv";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"")
                .contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8))
                .body(service.exportUsageCsv(startDate,endDate,venueId,status));
    }
}
