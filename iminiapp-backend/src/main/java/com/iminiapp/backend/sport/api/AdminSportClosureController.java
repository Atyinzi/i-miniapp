package com.iminiapp.backend.sport.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.sport.api.SportBookingModels.*;
import com.iminiapp.backend.sport.application.SportBookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/sports/closures")
public class AdminSportClosureController {
    private final SportBookingService service;
    public AdminSportClosureController(SportBookingService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<ClosureResponse>> list(@RequestParam(required = false) Long courtId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        return ApiResponse.success(service.closures(courtId, startDate, endDate), TraceIds.from(request));
    }
    @PostMapping
    public ApiResponse<ClosureResponse> create(@RequestBody ClosureCreateRequest body, HttpServletRequest request) {
        return ApiResponse.success("临时闭场创建成功",
                service.createClosure(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<ClosureResponse> cancel(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success("临时闭场已取消",
                service.cancelClosure(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }
}
