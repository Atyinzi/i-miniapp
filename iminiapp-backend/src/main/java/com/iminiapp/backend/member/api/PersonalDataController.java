package com.iminiapp.backend.member.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.member.application.PersonalDataService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.iminiapp.backend.member.api.PersonalDataModels.*;

@RestController
@RequestMapping("/api/v1/app")
public class PersonalDataController {
    private final PersonalDataService service;
    public PersonalDataController(PersonalDataService service) { this.service = service; }

    @GetMapping("/addresses")
    public ApiResponse<List<AddressResponse>> addresses(HttpServletRequest request) {
        return ApiResponse.success(service.addresses(CurrentPrincipal.from(request)), TraceIds.from(request));
    }
    @PostMapping("/addresses")
    public ApiResponse<List<AddressResponse>> createAddress(@RequestBody AddressRequest body, HttpServletRequest request) {
        return ApiResponse.success("地址已保存", service.createAddress(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/addresses/{id}")
    public ApiResponse<List<AddressResponse>> updateAddress(@PathVariable long id, @RequestBody AddressRequest body, HttpServletRequest request) {
        return ApiResponse.success("地址已保存", service.updateAddress(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }
    @PutMapping("/addresses/{id}/default")
    public ApiResponse<List<AddressResponse>> defaultAddress(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(service.defaultAddress(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }
    @DeleteMapping("/addresses/{id}")
    public ApiResponse<List<AddressResponse>> deleteAddress(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success("地址已删除", service.deleteAddress(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }

    @GetMapping("/travelers")
    public ApiResponse<List<TravelerResponse>> travelers(HttpServletRequest request) {
        return ApiResponse.success(service.travelers(CurrentPrincipal.from(request)), TraceIds.from(request));
    }
    @PostMapping("/travelers")
    public ApiResponse<List<TravelerResponse>> createTraveler(@RequestBody TravelerRequest body, HttpServletRequest request) {
        return ApiResponse.success("常用旅客已保存", service.createTraveler(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }
    @PutMapping("/travelers/{id}")
    public ApiResponse<List<TravelerResponse>> updateTraveler(@PathVariable long id, @RequestBody TravelerRequest body, HttpServletRequest request) {
        return ApiResponse.success("常用旅客已保存", service.updateTraveler(CurrentPrincipal.from(request), id, body), TraceIds.from(request));
    }
    @DeleteMapping("/travelers/{id}")
    public ApiResponse<List<TravelerResponse>> deleteTraveler(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success("常用旅客已删除", service.deleteTraveler(CurrentPrincipal.from(request), id), TraceIds.from(request));
    }
}
