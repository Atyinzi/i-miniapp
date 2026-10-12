package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.cultural.api.CulturalPromotionModels.PricingQuoteResponse;

import java.time.LocalDateTime;
import java.util.List;

public final class CulturalCommerceModels {
    private CulturalCommerceModels() {}

    public record AddressUpsertRequest(String receiverName, String receiverMobile, String province, String city,
                                       String district, String detailAddress, String postalCode, Boolean isDefault) {}
    public record AddressResponse(long id, String receiverName, String receiverMobile, String province, String city,
                                  String district, String detailAddress, String postalCode, boolean isDefault,
                                  LocalDateTime updatedAt) {}
    public record CartItemUpsertRequest(Long skuId, Integer quantity, Boolean selected) {}
    public record CartSelectionRequest(Boolean selected) {}
    public record CartItemResponse(long id, long skuId, String skuCode, String productName, String skuName,
                                   String coverUrl, long priceCent, int quantity, boolean selected,
                                   int availableQuantity, boolean purchasable, String unavailableReason,
                                   LocalDateTime updatedAt) {}
    public record CheckoutItem(Long skuId, Integer quantity) {}
    public record CheckoutRequest(Long addressId, List<CheckoutItem> items, String remark, Long userCouponId) {
        public CheckoutRequest(Long addressId, List<CheckoutItem> items, String remark) {
            this(addressId, items, remark, null);
        }
    }
    public record DeliverySnapshot(long addressId, String receiverName, String receiverMobile, String province,
                                   String city, String district, String detailAddress, String postalCode) {}
    public record CulturalOrderResponse(OrderResponse order, DeliverySnapshot delivery, PricingQuoteResponse pricing) {
        public CulturalOrderResponse(OrderResponse order, DeliverySnapshot delivery) {
            this(order, delivery, null);
        }
    }
}
