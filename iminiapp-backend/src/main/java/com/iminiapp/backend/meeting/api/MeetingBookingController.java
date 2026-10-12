package com.iminiapp.backend.meeting.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.BookingResponse;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.CreateBookingRequest;
import com.iminiapp.backend.meeting.application.MeetingBookingService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/meetings/bookings")
public class MeetingBookingController {
    private final MeetingBookingService service;

    public MeetingBookingController(MeetingBookingService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<BookingResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody CreateBookingRequest body,
                                               HttpServletRequest request) {
        return ApiResponse.success("会议预订草稿创建成功",
                service.create(CurrentPrincipal.from(request), idempotencyKey, body), TraceIds.from(request));
    }

    @GetMapping
    public ApiResponse<List<BookingResponse>> list(HttpServletRequest request) {
        return ApiResponse.success(service.list(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @GetMapping("/{bookingId}")
    public ApiResponse<BookingResponse> detail(@PathVariable long bookingId, HttpServletRequest request) {
        return ApiResponse.success(service.detail(CurrentPrincipal.from(request), bookingId), TraceIds.from(request));
    }

    @PostMapping("/{bookingId}/submit")
    public ApiResponse<BookingResponse> submit(@PathVariable long bookingId, HttpServletRequest request) {
        return ApiResponse.success("会议预订申请提交成功",
                service.submit(CurrentPrincipal.from(request), bookingId), TraceIds.from(request));
    }

    @PostMapping("/{bookingId}/cancel")
    public ApiResponse<BookingResponse> cancel(@PathVariable long bookingId, HttpServletRequest request) {
        return ApiResponse.success("会议预订申请取消成功",
                service.cancel(CurrentPrincipal.from(request), bookingId), TraceIds.from(request));
    }
}
