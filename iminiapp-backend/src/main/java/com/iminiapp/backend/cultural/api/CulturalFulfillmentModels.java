package com.iminiapp.backend.cultural.api;

import com.iminiapp.backend.cultural.api.CulturalCommerceModels.DeliverySnapshot;
import com.iminiapp.backend.order.api.OrderResponse;

import java.time.LocalDateTime;
import java.util.List;

public final class CulturalFulfillmentModels {
    private CulturalFulfillmentModels() {}

    public record CarrierUpsertRequest(String carrierCode, String carrierName, String trackingUrlTemplate,
                                       Boolean enabled, Integer sortOrder) {}
    public record CarrierResponse(long id, String carrierCode, String carrierName, String trackingUrlTemplate,
                                  boolean enabled, int sortOrder) {}
    public record PrepareRequest(String remark) {}
    public record ShipRequest(String carrierCode, String trackingNo, Integer autoReceiveDays, String remark) {}
    public record BatchShipItem(Long orderId, String carrierCode, String trackingNo, Integer autoReceiveDays,
                                String remark) {}
    public record BatchShipRequest(List<BatchShipItem> items) {}
    public record TrackingEventRequest(String eventCode, String eventDescription, String location,
                                       LocalDateTime occurredAt, String source, String externalEventId) {}
    public record TrackingEventResponse(long id, String eventCode, String eventDescription, String location,
                                        LocalDateTime occurredAt, String source) {}
    public record ShipmentResponse(long id, long orderId, String orderNo, String status, String carrierCode,
                                   String carrierName, String trackingNo, String trackingUrl,
                                   LocalDateTime preparedAt, LocalDateTime shippedAt,
                                   LocalDateTime autoReceiveAt, LocalDateTime receivedAt,
                                   boolean exceptionFlag, String exceptionReason,
                                   List<TrackingEventResponse> trackingEvents) {}
    public record CulturalAdminOrderItem(long orderId, String orderNo, long userId, String nickname,
                                         String status, long totalAmountCent, long discountAmountCent,
                                         long payableAmountCent, LocalDateTime createdAt,
                                         String receiverName, String receiverMobile, String fullAddress,
                                         String shipmentStatus, String carrierName, String trackingNo) {}
    public record CulturalAdminOrderDetail(OrderResponse order, DeliverySnapshot delivery,
                                           ShipmentResponse shipment) {}
}
