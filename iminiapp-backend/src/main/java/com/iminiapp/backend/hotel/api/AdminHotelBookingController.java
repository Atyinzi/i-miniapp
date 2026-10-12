package com.iminiapp.backend.hotel.api;

import com.iminiapp.backend.hotel.api.HotelBookingModels.InventoryAlertResponse;
import com.iminiapp.backend.hotel.application.HotelBookingService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/hotels/inventory-alerts")
public class AdminHotelBookingController {
    private final HotelBookingService service;
    public AdminHotelBookingController(HotelBookingService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<InventoryAlertResponse>> alerts(@RequestParam(required = false) String status,
                                                            HttpServletRequest request) {
        return ApiResponse.success(service.alerts(status), TraceIds.from(request));
    }

    @PostMapping("/{alertId}/resolve")
    public ApiResponse<InventoryAlertResponse> resolve(@PathVariable long alertId, HttpServletRequest request) {
        return ApiResponse.success("库存预警已处理", service.resolveAlert(alertId), TraceIds.from(request));
    }
}
