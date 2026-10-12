package com.iminiapp.backend.meeting.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.meeting.application.MeetingManagementService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.iminiapp.backend.meeting.api.MeetingManagementModels.*;

@RestController
@RequestMapping("/api/v1/admin/meetings")
public class AdminMeetingManagementController {
    private final MeetingManagementService service;
    public AdminMeetingManagementController(MeetingManagementService service) { this.service = service; }

    @GetMapping("/rooms") public ApiResponse<List<RoomResponse>> rooms(@RequestParam(required = false) String status, HttpServletRequest r) { return ApiResponse.success(service.rooms(status), TraceIds.from(r)); }
    @PostMapping("/rooms") public ApiResponse<RoomResponse> createRoom(@RequestBody RoomUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("会议室创建成功", service.createRoom(CurrentPrincipal.from(r), b), TraceIds.from(r)); }
    @PutMapping("/rooms/{id}") public ApiResponse<RoomResponse> updateRoom(@PathVariable long id, @RequestBody RoomUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("会议室更新成功", service.updateRoom(CurrentPrincipal.from(r), id, b), TraceIds.from(r)); }

    @GetMapping("/equipment") public ApiResponse<List<EquipmentResponse>> equipment(@RequestParam(required = false) Long roomId, @RequestParam(required = false) String status, HttpServletRequest r) { return ApiResponse.success(service.equipment(roomId, status), TraceIds.from(r)); }
    @PostMapping("/equipment") public ApiResponse<EquipmentResponse> createEquipment(@RequestBody EquipmentUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("会议室设备创建成功", service.createEquipment(CurrentPrincipal.from(r), b), TraceIds.from(r)); }
    @PutMapping("/equipment/{id}") public ApiResponse<EquipmentResponse> updateEquipment(@PathVariable long id, @RequestBody EquipmentUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("会议室设备更新成功", service.updateEquipment(CurrentPrincipal.from(r), id, b), TraceIds.from(r)); }

    @GetMapping("/opening-rules") public ApiResponse<List<OpeningRuleResponse>> openings(@RequestParam(required = false) Long roomId, HttpServletRequest r) { return ApiResponse.success(service.openings(roomId), TraceIds.from(r)); }
    @PostMapping("/opening-rules") public ApiResponse<OpeningRuleResponse> createOpening(@RequestBody OpeningRuleUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("可预订时段创建成功", service.createOpening(CurrentPrincipal.from(r), b), TraceIds.from(r)); }
    @PutMapping("/opening-rules/{id}") public ApiResponse<OpeningRuleResponse> updateOpening(@PathVariable long id, @RequestBody OpeningRuleUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("可预订时段更新成功", service.updateOpening(CurrentPrincipal.from(r), id, b), TraceIds.from(r)); }

    @GetMapping("/price-rules") public ApiResponse<List<PriceRuleResponse>> prices(@RequestParam(required = false) Long roomId, @RequestParam(required = false) String status, HttpServletRequest r) { return ApiResponse.success(service.prices(roomId, status), TraceIds.from(r)); }
    @PostMapping("/price-rules") public ApiResponse<PriceRuleResponse> createPrice(@RequestBody PriceRuleUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("收费规则创建成功", service.createPrice(CurrentPrincipal.from(r), b), TraceIds.from(r)); }
    @PutMapping("/price-rules/{id}") public ApiResponse<PriceRuleResponse> updatePrice(@PathVariable long id, @RequestBody PriceRuleUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("收费规则更新成功", service.updatePrice(CurrentPrincipal.from(r), id, b), TraceIds.from(r)); }

    @GetMapping("/services") public ApiResponse<List<ServiceResponse>> services(@RequestParam(required = false) String serviceType, @RequestParam(required = false) String status, HttpServletRequest r) { return ApiResponse.success(service.services(serviceType, status), TraceIds.from(r)); }
    @PostMapping("/services") public ApiResponse<ServiceResponse> createService(@RequestBody ServiceUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("配套服务创建成功", service.createService(CurrentPrincipal.from(r), b), TraceIds.from(r)); }
    @PutMapping("/services/{id}") public ApiResponse<ServiceResponse> updateService(@PathVariable long id, @RequestBody ServiceUpsertRequest b, HttpServletRequest r) { return ApiResponse.success("配套服务更新成功", service.updateService(CurrentPrincipal.from(r), id, b), TraceIds.from(r)); }
}
