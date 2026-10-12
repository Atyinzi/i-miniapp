package com.iminiapp.backend.hotel.api;

import com.iminiapp.backend.hotel.api.HotelBookingModels.*;
import com.iminiapp.backend.hotel.application.HotelBookingService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.refund.api.RefundApplicationResponse;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/hotel")
public class HotelBookingController {
    private final HotelBookingService service;
    public HotelBookingController(HotelBookingService service) { this.service = service; }

    @PostMapping("/quote")
    public ApiResponse<QuoteResponse> quote(@RequestBody QuoteRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.quote(body), TraceIds.from(request));
    }

    @PostMapping("/bookings")
    public ApiResponse<BookingResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody CreateBookingRequest body,
                                               HttpServletRequest request) {
        return ApiResponse.success("住宿预订创建成功",
                service.create(CurrentPrincipal.from(request), idempotencyKey, body), TraceIds.from(request));
    }

    @GetMapping("/bookings")
    public ApiResponse<List<BookingResponse>> mine(HttpServletRequest request) {
        return ApiResponse.success(service.mine(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @GetMapping("/bookings/{reservationId}")
    public ApiResponse<BookingResponse> detail(@PathVariable long reservationId, HttpServletRequest request) {
        return ApiResponse.success(service.detail(CurrentPrincipal.from(request), reservationId), TraceIds.from(request));
    }

    @PostMapping("/bookings/{reservationId}/cancel")
    public ApiResponse<BookingResponse> cancel(@PathVariable long reservationId, HttpServletRequest request) {
        return ApiResponse.success("住宿订单取消成功",
                service.cancel(CurrentPrincipal.from(request), reservationId), TraceIds.from(request));
    }

    @PutMapping("/bookings/{reservationId}")
    public ApiResponse<BookingResponse> modify(@PathVariable long reservationId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody ModifyBookingRequest body,
                                               HttpServletRequest request) {
        return ApiResponse.success("住宿订单改订成功",
                service.modify(CurrentPrincipal.from(request), reservationId, idempotencyKey, body), TraceIds.from(request));
    }

    @PostMapping("/bookings/{reservationId}/refund-applications")
    public ApiResponse<RefundApplicationResponse> refund(@PathVariable long reservationId,
                                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @RequestBody RefundReasonRequest body,
                                                         HttpServletRequest request) {
        return ApiResponse.success("住宿退款申请已提交", service.applyRefund(CurrentPrincipal.from(request),
                reservationId, idempotencyKey, body == null ? null : body.reason()), TraceIds.from(request));
    }
}
