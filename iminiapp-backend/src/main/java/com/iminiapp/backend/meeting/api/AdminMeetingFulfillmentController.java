package com.iminiapp.backend.meeting.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.meeting.api.MeetingFulfillmentModels.*;
import com.iminiapp.backend.meeting.application.MeetingFulfillmentService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.verification.api.ConsumeVerificationRequest;
import com.iminiapp.backend.verification.api.VerificationTicketResponse;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/meetings")
public class AdminMeetingFulfillmentController {
    private final MeetingFulfillmentService service;
    private final VerificationTicketService ticketService;
    public AdminMeetingFulfillmentController(MeetingFulfillmentService service, VerificationTicketService ticketService){this.service=service;this.ticketService=ticketService;}

    @PostMapping("/verifications/consume")
    public ApiResponse<VerificationTicketResponse> consume(@RequestBody ConsumeVerificationRequest body, HttpServletRequest request){return ApiResponse.success("会议核销成功",ticketService.consume(CurrentPrincipal.from(request),body==null?null:body.code(),body==null?null:body.deviceNo()),TraceIds.from(request));}
    @PostMapping("/verifications/{id}/revoke")
    public ApiResponse<UsageRecordResponse> revoke(@PathVariable long id,@RequestBody RevokeRequest body,HttpServletRequest request){return ApiResponse.success("会议核销已撤销",service.revoke(CurrentPrincipal.from(request),id,body),TraceIds.from(request));}
    @PostMapping("/bookings/{id}/complete")
    public ApiResponse<UsageRecordResponse> complete(@PathVariable long id,HttpServletRequest request){return ApiResponse.success("会议使用已结束",service.complete(CurrentPrincipal.from(request),id),TraceIds.from(request));}
    @GetMapping("/verifications")
    public ApiResponse<List<VerificationLogResponse>> logs(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,HttpServletRequest request){return ApiResponse.success(service.verificationLogs(CurrentPrincipal.from(request),startDate,endDate),TraceIds.from(request));}
    @GetMapping("/usage-records")
    public ApiResponse<List<UsageRecordResponse>> usage(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,HttpServletRequest request){return ApiResponse.success(service.usageRecords(CurrentPrincipal.from(request),startDate,endDate),TraceIds.from(request));}
}
