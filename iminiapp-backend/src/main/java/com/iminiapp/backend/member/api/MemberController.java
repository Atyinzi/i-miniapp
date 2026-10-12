package com.iminiapp.backend.member.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.member.application.MemberService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import static com.iminiapp.backend.member.api.MemberModels.*;

@RestController
@RequestMapping("/api/members/me")
public class MemberController {
    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MemberProfileResponse> mine(HttpServletRequest request) {
        return ApiResponse.success(service.mine(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @PostMapping("/upgrade-check")
    public ApiResponse<MemberProfileResponse> upgrade(HttpServletRequest request) {
        return ApiResponse.success(service.checkUpgrade(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

}
