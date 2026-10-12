package com.iminiapp.backend.identity.api;

import com.iminiapp.backend.identity.application.AdminAccountManagementService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/admin-accounts")
public class AdminAccountController {
    private final AdminAccountManagementService service;

    public AdminAccountController(AdminAccountManagementService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<AdminAccountResponse>> list(HttpServletRequest request) {
        return ApiResponse.success(service.list(), TraceIds.from(request));
    }

    @PostMapping
    public ApiResponse<AdminAccountResponse> create(@RequestBody AdminAccountCreateRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("管理员账号已创建", service.create(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AdminAccountResponse> status(@PathVariable long id,
            @RequestBody AdminAccountStatusRequest body, HttpServletRequest request) {
        return ApiResponse.success("账号状态已更新", service.updateStatus(CurrentPrincipal.from(request), id,
                body == null ? null : body.status()), TraceIds.from(request));
    }

    @PutMapping("/{id}/password")
    public ApiResponse<Void> password(@PathVariable long id, @RequestBody AdminPasswordResetRequest body,
            HttpServletRequest request) {
        service.resetPassword(CurrentPrincipal.from(request), id, body == null ? null : body.password());
        return ApiResponse.success("密码已重置，原登录会话已失效", null, TraceIds.from(request));
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<AdminAccountResponse> roles(@PathVariable long id, @RequestBody AdminAccountRolesRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("账号角色已更新", service.updateRoles(CurrentPrincipal.from(request), id,
                body == null ? null : body.roles()), TraceIds.from(request));
    }
}
