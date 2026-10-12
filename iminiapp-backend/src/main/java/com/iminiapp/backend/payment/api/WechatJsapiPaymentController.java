package com.iminiapp.backend.payment.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.payment.application.WechatJsapiPaymentService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app/orders")
public class WechatJsapiPaymentController {
    private final WechatJsapiPaymentService service;

    public WechatJsapiPaymentController(WechatJsapiPaymentService service) {
        this.service = service;
    }

    @PostMapping("/{orderId}/wechat-pay/jsapi-prepay")
    public ApiResponse<WechatJsapiPrepayResponse> prepay(
            @PathVariable long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.success(service.prepay(CurrentPrincipal.from(request), orderId, idempotencyKey),
                TraceIds.from(request));
    }
}
