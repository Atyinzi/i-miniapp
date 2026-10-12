package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalCommerceModels.*;
import com.iminiapp.backend.cultural.application.CulturalCommerceService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app/cultural-store")
public class CulturalCommerceController {
    private final CulturalCommerceService service;

    public CulturalCommerceController(CulturalCommerceService service) {
        this.service = service;
    }

    @GetMapping("/addresses")
    public ApiResponse<List<AddressResponse>> addresses(HttpServletRequest request) {
        return ApiResponse.success(service.addresses(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @PostMapping("/addresses")
    public ApiResponse<AddressResponse> createAddress(@RequestBody AddressUpsertRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.success("收货地址创建成功",
                service.createAddress(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PutMapping("/addresses/{addressId}")
    public ApiResponse<AddressResponse> updateAddress(@PathVariable long addressId,
                                                      @RequestBody AddressUpsertRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.success("收货地址更新成功",
                service.updateAddress(CurrentPrincipal.from(request), addressId, body), TraceIds.from(request));
    }

    @DeleteMapping("/addresses/{addressId}")
    public ApiResponse<Void> deleteAddress(@PathVariable long addressId, HttpServletRequest request) {
        service.deleteAddress(CurrentPrincipal.from(request), addressId);
        return ApiResponse.success("收货地址删除成功", null, TraceIds.from(request));
    }

    @GetMapping("/cart")
    public ApiResponse<List<CartItemResponse>> cart(HttpServletRequest request) {
        return ApiResponse.success(service.cart(CurrentPrincipal.from(request)), TraceIds.from(request));
    }

    @PutMapping("/cart/items")
    public ApiResponse<CartItemResponse> putCartItem(@RequestBody CartItemUpsertRequest body,
                                                     HttpServletRequest request) {
        return ApiResponse.success("购物车已更新",
                service.putCartItem(CurrentPrincipal.from(request), body), TraceIds.from(request));
    }

    @PatchMapping("/cart/items/{skuId}/selection")
    public ApiResponse<CartItemResponse> selectCartItem(@PathVariable long skuId,
                                                        @RequestBody CartSelectionRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success("购物车选中状态已更新", service.selectCartItem(
                CurrentPrincipal.from(request), skuId, Boolean.TRUE.equals(body.selected())), TraceIds.from(request));
    }

    @DeleteMapping("/cart/items/{skuId}")
    public ApiResponse<Void> removeCartItem(@PathVariable long skuId, HttpServletRequest request) {
        service.removeCartItem(CurrentPrincipal.from(request), skuId);
        return ApiResponse.success("购物车商品已删除", null, TraceIds.from(request));
    }

    @PostMapping("/checkout")
    public ApiResponse<CulturalOrderResponse> checkout(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CheckoutRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("文创订单创建成功", service.checkout(
                CurrentPrincipal.from(request), idempotencyKey, body), TraceIds.from(request));
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<CulturalOrderResponse> orderDetail(@PathVariable long orderId,
                                                          HttpServletRequest request) {
        return ApiResponse.success(service.orderDetail(CurrentPrincipal.from(request), orderId),
                TraceIds.from(request));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ApiResponse<CulturalOrderResponse> cancelOrder(@PathVariable long orderId,
                                                          HttpServletRequest request) {
        return ApiResponse.success("文创订单取消成功",
                service.cancelOrder(CurrentPrincipal.from(request), orderId), TraceIds.from(request));
    }
}
