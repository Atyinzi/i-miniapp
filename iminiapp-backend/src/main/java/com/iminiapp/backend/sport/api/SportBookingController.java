package com.iminiapp.backend.sport.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.sport.api.SportBookingModels.*;
import com.iminiapp.backend.sport.application.SportBookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/app/sports")
public class SportBookingController {
    private final SportBookingService service;
    public SportBookingController(SportBookingService service) { this.service = service; }

    @GetMapping("/venues")
    public ApiResponse<List<PublicVenueResponse>> venues(HttpServletRequest request) {
        return ApiResponse.success(service.publicVenues(), TraceIds.from(request));
    }
    @GetMapping("/venues/{venueId}/courts")
    public ApiResponse<List<PublicCourtResponse>> courts(@PathVariable long venueId, HttpServletRequest request) {
        return ApiResponse.success(service.publicCourts(venueId), TraceIds.from(request));
    }
    @GetMapping("/courts/{courtId}/availability")
    public ApiResponse<List<AvailableSlotResponse>> availability(@PathVariable long courtId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {
        return ApiResponse.success(service.availability(courtId, date), TraceIds.from(request));
    }
    @PostMapping("/bookings")
    public ApiResponse<BookingResponse> create(@RequestHeader("Idempotency-Key") String key,
                                                @RequestBody CreateBookingRequest body,
                                                HttpServletRequest request) {
        return ApiResponse.success("体育场地预订订单创建成功",
                service.create(CurrentPrincipal.from(request), key, body), TraceIds.from(request));
    }
    @GetMapping("/bookings")
    public ApiResponse<PageResponse<BookingResponse>> mine(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize,
                                                            HttpServletRequest request) {
        return ApiResponse.success(service.mine(CurrentPrincipal.from(request), page, pageSize), TraceIds.from(request));
    }
    @GetMapping("/bookings/{orderId}")
    public ApiResponse<BookingResponse> detail(@PathVariable long orderId, HttpServletRequest request) {
        return ApiResponse.success(service.detail(CurrentPrincipal.from(request), orderId), TraceIds.from(request));
    }
    @PostMapping("/bookings/{orderId}/cancel")
    public ApiResponse<BookingResponse> cancel(@PathVariable long orderId, HttpServletRequest request) {
        return ApiResponse.success("体育场地预订已取消", service.cancel(CurrentPrincipal.from(request), orderId), TraceIds.from(request));
    }
}
