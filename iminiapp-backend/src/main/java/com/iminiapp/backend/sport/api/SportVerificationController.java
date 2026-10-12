package com.iminiapp.backend.sport.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.sport.api.SportVerificationModels.*;
import com.iminiapp.backend.sport.application.SportVerificationService;
import com.iminiapp.backend.verification.api.ConsumeVerificationRequest;
import com.iminiapp.backend.verification.api.VerificationTicketResponse;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
public class SportVerificationController {
    private final VerificationTicketService tickets;
    private final SportVerificationService sports;
    public SportVerificationController(VerificationTicketService tickets, SportVerificationService sports) {
        this.tickets=tickets;this.sports=sports;
    }

    @GetMapping("/api/v1/app/sports/bookings/{orderId}/verification-code")
    public ApiResponse<List<VerificationTicketResponse>> code(@PathVariable long orderId,HttpServletRequest request){
        return ApiResponse.success("体育预订核销凭证查询成功",tickets.listOwnedTickets(CurrentPrincipal.from(request),orderId),TraceIds.from(request));
    }
    @PostMapping("/api/v1/admin/sports/verifications/consume")
    public ApiResponse<VerificationTicketResponse> consume(@RequestBody ConsumeVerificationRequest body,HttpServletRequest request){
        return ApiResponse.success("体育预订核销成功",tickets.consume(CurrentPrincipal.from(request),body==null?null:body.code(),body==null?null:body.deviceNo()),TraceIds.from(request));
    }
    @PostMapping("/api/v1/admin/sports/bookings/{orderId}/complete")
    public ApiResponse<SportUsageResponse> complete(@PathVariable long orderId,HttpServletRequest request){
        return ApiResponse.success("场地使用已完成",sports.complete(CurrentPrincipal.from(request),orderId),TraceIds.from(request));
    }
    @GetMapping("/api/v1/admin/sports/verifications")
    public ApiResponse<PageResponse<SportVerificationRecordResponse>> records(
            @RequestParam(required=false) Long venueId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate bookingDate,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            HttpServletRequest request){
        return ApiResponse.success(sports.records(venueId,bookingDate,page,pageSize),TraceIds.from(request));
    }
}
