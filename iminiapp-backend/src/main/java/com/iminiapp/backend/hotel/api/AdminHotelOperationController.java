package com.iminiapp.backend.hotel.api;

import com.iminiapp.backend.hotel.api.HotelOperationModels.*;
import com.iminiapp.backend.hotel.application.HotelOperationReportService;
import com.iminiapp.backend.hotel.application.HotelOperationService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/hotels/operations")
public class AdminHotelOperationController {
    private final HotelOperationService service;
    private final HotelOperationReportService reportService;
    public AdminHotelOperationController(HotelOperationService service,HotelOperationReportService reportService){
        this.service=service;this.reportService=reportService;
    }

    @GetMapping("/bookings")
    public ApiResponse<List<AdminStaySummary>> bookings(@RequestParam(required=false) String status,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) Long hotelId,
            @RequestParam(required=false) LocalDate startDate,@RequestParam(required=false) LocalDate endDate,
            HttpServletRequest request){
        return ApiResponse.success(service.bookings(status,keyword,hotelId,startDate,endDate),TraceIds.from(request));
    }
    @GetMapping("/bookings/{reservationId}")
    public ApiResponse<AdminStayDetail> detail(@PathVariable long reservationId,HttpServletRequest request){
        return ApiResponse.success(service.detail(reservationId),TraceIds.from(request));
    }
    @PostMapping("/bookings/{reservationId}/check-in")
    public ApiResponse<AdminStayDetail> checkIn(@PathVariable long reservationId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody CheckInRequest body,HttpServletRequest request){
        return ApiResponse.success("入住办理成功",service.checkIn(CurrentPrincipal.from(request),reservationId,key,body),TraceIds.from(request));
    }
    @PostMapping("/check-in/by-code")
    public ApiResponse<AdminStayDetail> checkInByCode(@RequestHeader("Idempotency-Key") String key,
            @RequestBody CheckInByCodeRequest body,HttpServletRequest request){
        return ApiResponse.success("入住码核销成功",service.checkInByCode(CurrentPrincipal.from(request),key,body),TraceIds.from(request));
    }
    @PostMapping("/check-in/by-id-card")
    public ApiResponse<AdminStayDetail> checkInByIdCard(@RequestHeader("Idempotency-Key") String key,
            @RequestBody CheckInByIdCardRequest body,HttpServletRequest request){
        return ApiResponse.success("证件核验入住成功",service.checkInByIdCard(CurrentPrincipal.from(request),key,body),TraceIds.from(request));
    }
    @PostMapping("/bookings/{reservationId}/change-room")
    public ApiResponse<AdminStayDetail> changeRoom(@PathVariable long reservationId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody ChangeRoomRequest body,HttpServletRequest request){
        return ApiResponse.success("换房成功",service.changeRoom(CurrentPrincipal.from(request),reservationId,key,body),TraceIds.from(request));
    }
    @PostMapping("/bookings/{reservationId}/extend")
    public ApiResponse<ExtendStayResponse> extend(@PathVariable long reservationId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody ExtendStayRequest body,HttpServletRequest request){
        return ApiResponse.success("续住成功",service.extendStay(CurrentPrincipal.from(request),reservationId,key,body),TraceIds.from(request));
    }
    @PostMapping("/bookings/{reservationId}/checkout-quote")
    public ApiResponse<CheckoutQuoteResponse> checkoutQuote(@PathVariable long reservationId,
            @RequestBody(required=false) List<ChargeInput> charges,HttpServletRequest request){
        return ApiResponse.success(service.checkoutQuote(reservationId,charges),TraceIds.from(request));
    }
    @PostMapping("/bookings/{reservationId}/checkout")
    public ApiResponse<SettlementResponse> checkout(@PathVariable long reservationId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody CheckoutRequest body,HttpServletRequest request){
        return ApiResponse.success("退房结算成功",service.checkout(CurrentPrincipal.from(request),reservationId,key,body),TraceIds.from(request));
    }
    @PostMapping("/bookings/{reservationId}/no-show")
    public ApiResponse<AdminStayDetail> noShow(@PathVariable long reservationId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) NoteRequest body,HttpServletRequest request){
        return ApiResponse.success("已标记未到店",service.markNoShow(CurrentPrincipal.from(request),reservationId,key,body==null?null:body.note()),TraceIds.from(request));
    }
    @PostMapping("/rooms/{roomId}/clean-complete")
    public ApiResponse<RoomStatusResponse> clean(@PathVariable long roomId,HttpServletRequest request){
        return ApiResponse.success("房间清洁完成",service.completeCleaning(CurrentPrincipal.from(request),roomId),TraceIds.from(request));
    }
    @GetMapping("/warnings")
    public ApiResponse<List<OperationWarning>> warnings(HttpServletRequest request){
        return ApiResponse.success(service.warnings(),TraceIds.from(request));
    }
    @GetMapping("/report")
    public ApiResponse<HotelOperationReport> report(@RequestParam(required=false) Long hotelId,
            @RequestParam(required=false) LocalDate startDate,@RequestParam(required=false) LocalDate endDate,
            HttpServletRequest request){
        return ApiResponse.success(reportService.report(hotelId,startDate,endDate),TraceIds.from(request));
    }
}
