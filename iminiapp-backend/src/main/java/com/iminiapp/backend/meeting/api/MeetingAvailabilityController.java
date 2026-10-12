package com.iminiapp.backend.meeting.api;

import com.iminiapp.backend.meeting.application.MeetingAvailabilityService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;

@RestController
@RequestMapping("/api/v1/app/meetings")
public class MeetingAvailabilityController {
    private final MeetingAvailabilityService service;

    public MeetingAvailabilityController(MeetingAvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/rooms/{roomId}/availability")
    public ApiResponse<MeetingAvailabilityModels.AvailabilityResponse> availability(
            @PathVariable long roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam int attendees,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            HttpServletRequest request) {
        return ApiResponse.success(service.availability(roomId, date, attendees, startTime, endTime),
                TraceIds.from(request));
    }
}
