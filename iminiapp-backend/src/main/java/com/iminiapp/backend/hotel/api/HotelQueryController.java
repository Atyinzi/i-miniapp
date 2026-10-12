package com.iminiapp.backend.hotel.api;

import com.iminiapp.backend.hotel.api.HotelModels.*;
import com.iminiapp.backend.hotel.application.HotelQueryService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/app/hotels")
public class HotelQueryController {
    private final HotelQueryService service;
    public HotelQueryController(HotelQueryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<HotelSummary>> hotels(HttpServletRequest request) {
        return ApiResponse.success(service.hotels(), TraceIds.from(request));
    }

    @GetMapping("/{hotelId}")
    public ApiResponse<HotelDetail> detail(@PathVariable long hotelId, HttpServletRequest request) {
        return ApiResponse.success(service.detail(hotelId), TraceIds.from(request));
    }

    @GetMapping("/{hotelId}/room-types")
    public ApiResponse<List<PublicRoomType>> roomTypes(@PathVariable long hotelId,
                                                       @RequestParam(required = false) LocalDate checkInDate,
                                                       @RequestParam(required = false) LocalDate checkOutDate,
                                                       @RequestParam(defaultValue = "1") int roomCount,
                                                       HttpServletRequest request) {
        return ApiResponse.success(service.roomTypes(hotelId, checkInDate, checkOutDate, roomCount), TraceIds.from(request));
    }
}
