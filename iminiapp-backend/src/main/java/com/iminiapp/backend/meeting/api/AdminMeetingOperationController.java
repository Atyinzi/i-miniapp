package com.iminiapp.backend.meeting.api;

import com.iminiapp.backend.meeting.api.MeetingOperationModels.*;
import com.iminiapp.backend.meeting.application.MeetingOperationService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/meetings")
public class AdminMeetingOperationController {
    private final MeetingOperationService service;
    public AdminMeetingOperationController(MeetingOperationService service){this.service=service;}

    @GetMapping("/bookings")
    public ApiResponse<List<BookingLedgerResponse>> bookings(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,@RequestParam(required=false) Long roomId,
        @RequestParam(required=false) String status,@RequestParam(required=false) String bookingType,@RequestParam(required=false) String keyword,HttpServletRequest request){
        return ApiResponse.success(service.bookings(startDate,endDate,roomId,status,bookingType,keyword),TraceIds.from(request));}

    @GetMapping("/reports/overview")
    public ApiResponse<OverviewResponse> overview(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,HttpServletRequest request){return ApiResponse.success(service.overview(startDate,endDate),TraceIds.from(request));}
    @GetMapping("/reports/room-utilization")
    public ApiResponse<List<RoomUtilizationResponse>> rooms(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,HttpServletRequest request){return ApiResponse.success(service.roomUtilization(startDate,endDate),TraceIds.from(request));}
    @GetMapping("/reports/services")
    public ApiResponse<List<ServiceSalesResponse>> services(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,HttpServletRequest request){return ApiResponse.success(service.serviceSales(startDate,endDate),TraceIds.from(request));}
    @GetMapping(value="/reports/bookings.csv",produces="text/csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,@RequestParam(required=false) Long roomId,
        @RequestParam(required=false) String status,@RequestParam(required=false) String bookingType,@RequestParam(required=false) String keyword){
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=meeting-bookings.csv")
                .contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8))
                .body(service.exportCsv(startDate,endDate,roomId,status,bookingType,keyword));}
}
