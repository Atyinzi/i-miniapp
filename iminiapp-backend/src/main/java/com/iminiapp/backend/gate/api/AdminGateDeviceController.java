package com.iminiapp.backend.gate.api;

import com.iminiapp.backend.gate.application.GateDeviceService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/gate-devices")
public class AdminGateDeviceController {
    private final GateDeviceService service;

    public AdminGateDeviceController(GateDeviceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<GateDeviceResponse>> list(HttpServletRequest request) {
        return ApiResponse.success(service.list(), TraceIds.from(request));
    }

    @PostMapping
    public ApiResponse<GateDeviceResponse> create(@RequestBody GateDeviceCreateRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.success("设备已创建；密钥只展示本次，请立即安全保存",
                service.create(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<GateDeviceResponse> update(@PathVariable long id,
                                                   @RequestBody GateDeviceUpdateRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.success(service.update(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @PostMapping("/{id}/rotate-secret")
    public ApiResponse<GateDeviceResponse> rotateSecret(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success("密钥已轮换，设备已自动停用；新密钥只展示本次",
                service.rotateSecret(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }

    @GetMapping("/events")
    public ApiResponse<List<GateEventResponse>> events(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ApiResponse.success(service.events(deviceId, decision, limit), TraceIds.from(request));
    }
}
