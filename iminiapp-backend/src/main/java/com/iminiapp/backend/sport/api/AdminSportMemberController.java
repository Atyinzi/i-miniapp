package com.iminiapp.backend.sport.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.sport.api.SportMemberModels.*;
import com.iminiapp.backend.sport.application.SportMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/sports/members")
public class AdminSportMemberController {
    private final SportMemberService service;
    public AdminSportMemberController(SportMemberService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResponse<AdminMemberResponse>> members(@RequestParam(required=false) String levelCode,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,HttpServletRequest request) {
        return ApiResponse.success(service.adminMembers(levelCode,status,page,pageSize),TraceIds.from(request));
    }
    @GetMapping("/{memberId}/transactions")
    public ApiResponse<PageResponse<WalletTransactionResponse>> transactions(@PathVariable long memberId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(service.adminTransactions(memberId,page,pageSize),TraceIds.from(request));
    }

    @GetMapping("/levels")
    public ApiResponse<List<MemberLevelResponse>> levels(@RequestParam(required=false) String status,
                                                         HttpServletRequest request) {
        return ApiResponse.success(service.levels(status), TraceIds.from(request));
    }
    @PostMapping("/levels")
    public ApiResponse<MemberLevelResponse> createLevel(@RequestBody MemberLevelUpsertRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("会员等级创建成功", service.createLevel(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/levels/{id}")
    public ApiResponse<MemberLevelResponse> updateLevel(@PathVariable long id,
                                                        @RequestBody MemberLevelUpsertRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("会员等级更新成功", service.updateLevel(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }
    @PutMapping("/{memberId}/level")
    public ApiResponse<MemberResponse> assignLevel(@PathVariable long memberId,
                                                   @RequestBody AssignLevelRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.success("会员等级调整成功", service.assignLevel(CurrentPrincipal.from(request), memberId, body), TraceIds.from(request));
    }
}
