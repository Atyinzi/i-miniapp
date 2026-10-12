package com.iminiapp.backend.verification.api;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StaffVerificationController {
    private final VerificationTicketService service;

    public StaffVerificationController(VerificationTicketService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/admin/verifications/consume")
    public ApiResponse<VerificationTicketResponse> consume(
            @RequestBody ConsumeVerificationRequest body, HttpServletRequest request) {
        AuthenticatedPrincipal principal = CurrentPrincipal.from(request);
        if (!"ADMIN".equals(principal.accountType()) || !principal.hasPermission("verification.use")) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN,
                    "Current administrator account cannot consume tickets");
        }
        return ApiResponse.success("核销成功",
                service.consume(principal, body == null ? null : body.code(),
                        body == null ? null : body.deviceNo()),
                TraceIds.from(request));
    }
}
