package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalFulfillmentModels.*;
import com.iminiapp.backend.cultural.application.CulturalFulfillmentService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/cultural-store")
public class AdminCulturalFulfillmentController {
    private final CulturalFulfillmentService service;

    public AdminCulturalFulfillmentController(CulturalFulfillmentService service) { this.service = service; }

    @GetMapping("/carriers")
    public ApiResponse<List<CarrierResponse>> carriers(HttpServletRequest request) {
        return ApiResponse.success(service.carriers(false), TraceIds.from(request));
    }

    @PostMapping("/carriers")
    public ApiResponse<CarrierResponse> createCarrier(@RequestBody CarrierUpsertRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.success("物流公司创建成功",
                service.createCarrier(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PutMapping("/carriers/{carrierId}")
    public ApiResponse<CarrierResponse> updateCarrier(@PathVariable long carrierId,
                                                      @RequestBody CarrierUpsertRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.success("物流公司更新成功",
                service.updateCarrier(CurrentPrincipal.from(request), carrierId, body), TraceIds.from(request));
    }

    @GetMapping("/fulfillment/orders")
    public ApiResponse<PageResponse<CulturalAdminOrderItem>> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(service.adminOrders(status, keyword, page, pageSize), TraceIds.from(request));
    }

    @GetMapping("/fulfillment/orders/{orderId}")
    public ApiResponse<CulturalAdminOrderDetail> orderDetail(@PathVariable long orderId,
                                                             HttpServletRequest request) {
        return ApiResponse.success(service.adminOrderDetail(orderId), TraceIds.from(request));
    }

    @PostMapping("/fulfillment/orders/{orderId}/prepare")
    public ApiResponse<ShipmentResponse> prepare(@PathVariable long orderId,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @RequestBody(required = false) PrepareRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.success("订单进入备货状态", service.prepare(
                CurrentPrincipal.from(request), orderId, key, body), TraceIds.from(request));
    }

    @PostMapping("/fulfillment/orders/{orderId}/ship")
    public ApiResponse<ShipmentResponse> ship(@PathVariable long orderId,
                                              @RequestHeader("Idempotency-Key") String key,
                                              @RequestBody ShipRequest body,
                                              HttpServletRequest request) {
        return ApiResponse.success("订单发货成功", service.ship(
                CurrentPrincipal.from(request), orderId, key, body), TraceIds.from(request));
    }

    @PostMapping("/fulfillment/orders/batch-ship")
    public ApiResponse<List<ShipmentResponse>> batchShip(@RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody BatchShipRequest body,
                                                         HttpServletRequest request) {
        return ApiResponse.success("批量发货成功", service.batchShip(
                CurrentPrincipal.from(request), key, body), TraceIds.from(request));
    }

    @PutMapping("/fulfillment/orders/{orderId}/shipment")
    public ApiResponse<ShipmentResponse> updateShipment(@PathVariable long orderId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody ShipRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("物流信息更新成功", service.updateShipment(
                CurrentPrincipal.from(request), orderId, key, body), TraceIds.from(request));
    }

    @PostMapping("/fulfillment/orders/{orderId}/tracking-events")
    public ApiResponse<ShipmentResponse> appendTracking(@PathVariable long orderId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody TrackingEventRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("物流轨迹更新成功", service.appendTracking(
                CurrentPrincipal.from(request), orderId, key, body), TraceIds.from(request));
    }

    @PostMapping("/fulfillment/auto-receive-overdue")
    public ApiResponse<Map<String, Integer>> autoReceive(HttpServletRequest request) {
        return ApiResponse.success("自动收货扫描完成",
                Map.of("completedCount", service.autoReceiveOverdue()), TraceIds.from(request));
    }
}
