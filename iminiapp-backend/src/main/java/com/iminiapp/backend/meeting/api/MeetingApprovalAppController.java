package com.iminiapp.backend.meeting.api;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.meeting.api.MeetingApprovalModels.ProgressResponse;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.BookingResponse;
import com.iminiapp.backend.meeting.application.MeetingApprovalService;
import com.iminiapp.backend.meeting.application.MeetingBookingService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/app/meetings/bookings")
public class MeetingApprovalAppController {
    private final MeetingApprovalService approvalService;
    private final MeetingBookingService bookingService;

    public MeetingApprovalAppController(MeetingApprovalService approvalService,
                                        MeetingBookingService bookingService) {
        this.approvalService = approvalService;
        this.bookingService = bookingService;
    }

    @GetMapping("/{bookingId}/approval")
    public ApiResponse<ProgressResponse> approval(@PathVariable long bookingId, HttpServletRequest request) {
        return ApiResponse.success(approvalService.applicantProgress(
                CurrentPrincipal.from(request), bookingId), TraceIds.from(request));
    }

    @PostMapping("/{bookingId}/withdraw")
    public ApiResponse<BookingResponse> withdraw(@PathVariable long bookingId, HttpServletRequest request) {
        AuthenticatedPrincipal principal = CurrentPrincipal.from(request);
        approvalService.withdraw(principal, bookingId);
        return ApiResponse.success("内部会议申请已撤回",
                bookingService.detail(principal, bookingId), TraceIds.from(request));
    }
}
