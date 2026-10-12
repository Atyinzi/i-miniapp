package com.iminiapp.backend.scenic.api;

import com.iminiapp.backend.scenic.application.ScenicPackageQueryService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app/scenic-packages")
public class ScenicPackageController {
    private final ScenicPackageQueryService service;

    public ScenicPackageController(ScenicPackageQueryService service) {
        this.service = service;
    }

    @GetMapping("/{packageSkuId}")
    public ApiResponse<ScenicPackageResponse> detail(@PathVariable long packageSkuId,
                                                      HttpServletRequest request) {
        return ApiResponse.success(service.detail(packageSkuId), TraceIds.from(request));
    }
}
