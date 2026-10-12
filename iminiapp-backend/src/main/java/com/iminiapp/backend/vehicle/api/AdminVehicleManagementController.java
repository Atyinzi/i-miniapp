package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleAdminManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminVehicleManagementController {
    private final VehicleAdminManagementService service;

    public AdminVehicleManagementController(VehicleAdminManagementService service) {
        this.service = service;
    }

    @PutMapping("/vehicles/{vehicleId}")
    public ApiResponse<VehicleResponse> updateVehicle(@PathVariable long vehicleId,
                                                       @RequestBody VehicleAdminUpdateRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.success("车辆档案已更新",
                service.updateVehicle(CurrentPrincipal.from(request), vehicleId, body), TraceIds.from(request));
    }

    @PutMapping("/vehicle-models/{modelId}/tariff")
    public ApiResponse<Void> updateTariff(@PathVariable long modelId,
                                          @RequestBody VehicleTariffUpdateRequest body,
                                          HttpServletRequest request) {
        service.updateTariff(CurrentPrincipal.from(request), modelId, body);
        return ApiResponse.success("车型价格规则已更新", null, TraceIds.from(request));
    }
}
