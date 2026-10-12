package com.iminiapp.backend.sport.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.sport.api.SportVenueModels.*;
import com.iminiapp.backend.sport.application.SportVenueService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/sports")
public class AdminSportVenueController {
    private final SportVenueService service;

    public AdminSportVenueController(SportVenueService service) { this.service = service; }

    @GetMapping("/venues")
    public ApiResponse<List<VenueResponse>> venues(@RequestParam(required = false) String status,
                                                    HttpServletRequest request) {
        return ApiResponse.success(service.venues(status), TraceIds.from(request));
    }
    @PostMapping("/venues")
    public ApiResponse<VenueResponse> createVenue(@RequestBody VenueUpsertRequest body, HttpServletRequest request) {
        return ApiResponse.success("体育场馆创建成功", service.createVenue(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/venues/{id}")
    public ApiResponse<VenueResponse> updateVenue(@PathVariable long id, @RequestBody VenueUpsertRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.success("体育场馆更新成功", service.updateVenue(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/court-types")
    public ApiResponse<List<CourtTypeResponse>> courtTypes(@RequestParam(required = false) Long venueId,
                                                            @RequestParam(required = false) String status,
                                                            HttpServletRequest request) {
        return ApiResponse.success(service.courtTypes(venueId, status), TraceIds.from(request));
    }
    @PostMapping("/court-types")
    public ApiResponse<CourtTypeResponse> createCourtType(@RequestBody CourtTypeUpsertRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.success("场地类型创建成功", service.createCourtType(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/court-types/{id}")
    public ApiResponse<CourtTypeResponse> updateCourtType(@PathVariable long id, @RequestBody CourtTypeUpsertRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.success("场地类型更新成功", service.updateCourtType(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/courts")
    public ApiResponse<List<CourtResponse>> courts(@RequestParam(required = false) Long venueId,
                                                    @RequestParam(required = false) Long courtTypeId,
                                                    @RequestParam(required = false) String status,
                                                    HttpServletRequest request) {
        return ApiResponse.success(service.courts(venueId, courtTypeId, status), TraceIds.from(request));
    }
    @PostMapping("/courts")
    public ApiResponse<CourtResponse> createCourt(@RequestBody CourtUpsertRequest body, HttpServletRequest request) {
        return ApiResponse.success("体育场地创建成功", service.createCourt(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/courts/{id}")
    public ApiResponse<CourtResponse> updateCourt(@PathVariable long id, @RequestBody CourtUpsertRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.success("体育场地更新成功", service.updateCourt(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/opening-rules")
    public ApiResponse<List<OpeningRuleResponse>> openingRules(@RequestParam(required = false) Long courtId,
                                                                HttpServletRequest request) {
        return ApiResponse.success(service.openingRules(courtId), TraceIds.from(request));
    }
    @PostMapping("/opening-rules")
    public ApiResponse<OpeningRuleResponse> createOpeningRule(@RequestBody OpeningRuleUpsertRequest body,
                                                               HttpServletRequest request) {
        return ApiResponse.success("开放时段规则创建成功", service.createOpeningRule(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/opening-rules/{id}")
    public ApiResponse<OpeningRuleResponse> updateOpeningRule(@PathVariable long id,
                                                               @RequestBody OpeningRuleUpsertRequest body,
                                                               HttpServletRequest request) {
        return ApiResponse.success("开放时段规则更新成功", service.updateOpeningRule(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }

    @GetMapping("/price-rules")
    public ApiResponse<List<PriceRuleResponse>> priceRules(@RequestParam(required = false) Long venueId,
                                                            @RequestParam(required = false) Long courtTypeId,
                                                            @RequestParam(required = false) Long courtId,
                                                            @RequestParam(required = false) String status,
                                                            HttpServletRequest request) {
        return ApiResponse.success(service.priceRules(venueId, courtTypeId, courtId, status), TraceIds.from(request));
    }
    @PostMapping("/price-rules")
    public ApiResponse<PriceRuleResponse> createPriceRule(@RequestBody PriceRuleUpsertRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.success("场地价格规则创建成功", service.createPriceRule(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/price-rules/{id}")
    public ApiResponse<PriceRuleResponse> updatePriceRule(@PathVariable long id, @RequestBody PriceRuleUpsertRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.success("场地价格规则更新成功", service.updatePriceRule(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }
}
