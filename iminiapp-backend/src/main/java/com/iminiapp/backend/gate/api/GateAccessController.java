package com.iminiapp.backend.gate.api;

import com.iminiapp.backend.gate.application.GateAccessService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/gate/devices/{deviceNo}")
public class GateAccessController {
    private final GateAccessService service;

    public GateAccessController(GateAccessService service) {
        this.service = service;
    }

    @PostMapping("/consume")
    public ApiResponse<GateDecisionResponse> consume(
            @PathVariable String deviceNo,
            @RequestHeader("X-Gate-Timestamp") String timestamp,
            @RequestHeader("X-Gate-Nonce") String nonce,
            @RequestHeader("X-Gate-Signature") String signature,
            @RequestBody GateConsumeRequest body,
            HttpServletRequest request) {
        GateDecisionResponse result = service.consume(deviceNo, timestamp, nonce, signature, body);
        return ApiResponse.success(result.granted() ? "允许通行" : "拒绝通行", result, TraceIds.from(request));
    }
}
