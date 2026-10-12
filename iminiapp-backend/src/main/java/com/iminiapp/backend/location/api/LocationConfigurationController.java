package com.iminiapp.backend.location.api;

import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.location.application.LocationConfigurationService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class LocationConfigurationController {
    private final LocationConfigurationService service;
    public LocationConfigurationController(LocationConfigurationService service){this.service=service;}

    @GetMapping("/api/v1/app/location-places")
    public ApiResponse<List<LocationPlaceResponse>> publicPlaces(@RequestParam(required=false) String type,HttpServletRequest request){return ApiResponse.success(service.publicPlaces(type),TraceIds.from(request));}
    @GetMapping("/api/v1/admin/location-places")
    public ApiResponse<List<LocationPlaceResponse>> all(HttpServletRequest request){return ApiResponse.success(service.allPlaces(),TraceIds.from(request));}
    @PostMapping("/api/v1/admin/location-places")
    public ApiResponse<LocationPlaceResponse> create(@RequestBody LocationPlaceRequest body,HttpServletRequest request){return ApiResponse.success("地图地点已创建",service.create(CurrentPrincipal.from(request),body),TraceIds.from(request));}
    @PutMapping("/api/v1/admin/location-places/{id}")
    public ApiResponse<LocationPlaceResponse> update(@PathVariable long id,@RequestBody LocationPlaceRequest body,HttpServletRequest request){return ApiResponse.success("地图地点已更新",service.update(CurrentPrincipal.from(request),id,body),TraceIds.from(request));}
    @GetMapping("/api/v1/admin/dining-delivery-scopes/{storeId}")
    public ApiResponse<DiningDeliveryScopeResponse> deliveryScope(@PathVariable long storeId,HttpServletRequest request){return ApiResponse.success(service.deliveryScope(storeId),TraceIds.from(request));}
    @PutMapping("/api/v1/admin/dining-delivery-scopes/{storeId}")
    public ApiResponse<DiningDeliveryScopeResponse> updateDeliveryScope(@PathVariable long storeId,@RequestBody DiningDeliveryScopeRequest body,HttpServletRequest request){return ApiResponse.success("内部配送区域已更新",service.updateDeliveryScope(CurrentPrincipal.from(request),storeId,body),TraceIds.from(request));}
}
