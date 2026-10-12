package com.iminiapp.backend.hotel.api;

import com.iminiapp.backend.hotel.api.HotelModels.*;
import com.iminiapp.backend.hotel.application.HotelManagementService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/hotels")
public class AdminHotelController {
    private final HotelManagementService service;
    public AdminHotelController(HotelManagementService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<HotelResponse>> hotels(@RequestParam(required = false) String status,
                                                   HttpServletRequest request) {
        return ApiResponse.success(service.hotels(status), TraceIds.from(request));
    }
    @PostMapping
    public ApiResponse<HotelResponse> createHotel(@RequestBody HotelUpsertRequest body, HttpServletRequest request) {
        return ApiResponse.success("酒店创建成功", service.createHotel(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/{id}")
    public ApiResponse<HotelResponse> updateHotel(@PathVariable long id, @RequestBody HotelUpsertRequest body,
                                                  HttpServletRequest request) {
        return ApiResponse.success("酒店更新成功", service.updateHotel(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/room-types")
    public ApiResponse<List<RoomTypeResponse>> roomTypes(@RequestParam(required = false) Long hotelId,
                                                         @RequestParam(required = false) String status,
                                                         HttpServletRequest request) {
        return ApiResponse.success(service.roomTypes(hotelId, status), TraceIds.from(request));
    }
    @PostMapping("/room-types")
    public ApiResponse<RoomTypeResponse> createRoomType(@RequestBody RoomTypeUpsertRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("房型创建成功", service.createRoomType(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/room-types/{id}")
    public ApiResponse<RoomTypeResponse> updateRoomType(@PathVariable long id, @RequestBody RoomTypeUpsertRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("房型更新成功", service.updateRoomType(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/rooms")
    public ApiResponse<List<RoomResponse>> rooms(@RequestParam(required = false) Long hotelId,
                                                 @RequestParam(required = false) Long roomTypeId,
                                                 @RequestParam(required = false) String status,
                                                 HttpServletRequest request) {
        return ApiResponse.success(service.rooms(hotelId, roomTypeId, status), TraceIds.from(request));
    }
    @PostMapping("/rooms")
    public ApiResponse<RoomResponse> createRoom(@RequestBody RoomUpsertRequest body, HttpServletRequest request) {
        return ApiResponse.success("实体房间创建成功", service.createRoom(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/rooms/{id}")
    public ApiResponse<RoomResponse> updateRoom(@PathVariable long id, @RequestBody RoomUpsertRequest body,
                                                HttpServletRequest request) {
        return ApiResponse.success("实体房间更新成功", service.updateRoom(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/price-rules")
    public ApiResponse<List<PriceRuleResponse>> priceRules(@RequestParam(required = false) Long roomTypeId,
                                                           @RequestParam(required = false) String status,
                                                           HttpServletRequest request) {
        return ApiResponse.success(service.priceRules(roomTypeId, status), TraceIds.from(request));
    }
    @PostMapping("/price-rules")
    public ApiResponse<PriceRuleResponse> createPriceRule(@RequestBody PriceRuleUpsertRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.success("住宿价格规则创建成功", service.createPriceRule(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/price-rules/{id}")
    public ApiResponse<PriceRuleResponse> updatePriceRule(@PathVariable long id, @RequestBody PriceRuleUpsertRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.success("住宿价格规则更新成功", service.updatePriceRule(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @PostMapping("/inventory/calendar/generate")
    public ApiResponse<CalendarGenerateResponse> generateCalendar(@RequestBody CalendarGenerateRequest body,
                                                                  HttpServletRequest request) {
        return ApiResponse.success("价格库存日历生成成功",
                service.generateCalendar(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @GetMapping("/room-types/{roomTypeId}/inventory")
    public ApiResponse<List<InventoryDayResponse>> calendar(@PathVariable long roomTypeId,
                                                            @RequestParam LocalDate startDate,
                                                            @RequestParam LocalDate endDate,
                                                            HttpServletRequest request) {
        return ApiResponse.success(service.calendar(roomTypeId, startDate, endDate), TraceIds.from(request));
    }
    @PutMapping("/room-types/{roomTypeId}/inventory/{date}")
    public ApiResponse<InventoryDayResponse> updateInventory(@PathVariable long roomTypeId,
                                                             @PathVariable LocalDate date,
                                                             @RequestBody InventoryDayUpsertRequest body,
                                                             HttpServletRequest request) {
        return ApiResponse.success("房型日库存更新成功",
                service.updateInventoryDay(CurrentPrincipal.from(request), roomTypeId, date, body), TraceIds.from(request));
    }
}
