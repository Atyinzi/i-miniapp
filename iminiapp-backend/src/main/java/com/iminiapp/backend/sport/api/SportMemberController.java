package com.iminiapp.backend.sport.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.payment.api.PaymentResponse;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.sport.api.SportMemberModels.*;
import com.iminiapp.backend.sport.application.SportMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/app/sports/member")
public class SportMemberController {
    private final SportMemberService service;
    public SportMemberController(SportMemberService service) { this.service = service; }

    @PostMapping
    public ApiResponse<MemberResponse> register(HttpServletRequest request) {
        return ApiResponse.success("体育会员注册成功", service.register(CurrentPrincipal.from(request)), TraceIds.from(request));
    }
    @GetMapping
    public ApiResponse<MemberResponse> mine(HttpServletRequest request) {
        return ApiResponse.success(service.mine(CurrentPrincipal.from(request)), TraceIds.from(request));
    }
    @GetMapping("/wallet/transactions")
    public ApiResponse<PageResponse<WalletTransactionResponse>> transactions(@RequestParam(defaultValue="1") int page,
                                                                              @RequestParam(defaultValue="20") int pageSize,
                                                                              HttpServletRequest request) {
        return ApiResponse.success(service.transactions(CurrentPrincipal.from(request), page, pageSize), TraceIds.from(request));
    }
    @PostMapping("/bookings/{orderId}/balance-pay")
    public ApiResponse<PaymentResponse> balancePay(@PathVariable long orderId,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    HttpServletRequest request) {
        return ApiResponse.success("会员余额支付成功", service.balancePay(CurrentPrincipal.from(request), orderId, key), TraceIds.from(request));
    }
}
