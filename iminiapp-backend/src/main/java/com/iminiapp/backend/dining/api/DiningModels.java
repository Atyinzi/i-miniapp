package com.iminiapp.backend.dining.api;

import java.time.*;
import java.util.List;

public final class DiningModels {
 private DiningModels(){}
 public record StoreRequest(String storeCode,String name,String address,String contactPhone,LocalTime openTime,LocalTime closeTime,String status){}
 public record StoreResponse(long id,String storeCode,String name,String address,String deliveryScopeType,String deliveryScopeConfig,String contactPhone,LocalTime openTime,LocalTime closeTime,String status){}
 public record TableRequest(Long storeId,String tableCode,String name,String tableType,Integer capacity,String status){}
 public record TableResponse(long id,long storeId,String storeName,String tableCode,String name,String tableType,int capacity,String status){}
 public record CategoryRequest(Long storeId,String categoryCode,String name,Integer sortOrder,String status){}
 public record CategoryResponse(long id,long storeId,String storeName,String categoryCode,String name,int sortOrder,String status){}
 public record DishRequest(Long storeId,Long categoryId,String dishCode,String name,Long priceCent,Long specialPriceCent,String imageUrl,String description,Boolean recommended,Boolean special,String status,Integer initialStock,Integer warningQuantity){}
 public record DishResponse(long id,long storeId,String storeName,long categoryId,String categoryName,String dishCode,String name,long priceCent,Long specialPriceCent,long salePriceCent,String imageUrl,String description,boolean recommended,boolean special,String status,int onHandQuantity,int lockedQuantity,int availableQuantity,int warningQuantity,boolean lowStock){}
 public record PackageItemRequest(Long dishId,Integer quantity){}
 public record PackageRequest(Long storeId,String packageCode,String name,Long priceCent,String imageUrl,String description,Boolean recommended,String status,List<PackageItemRequest> items){}
 public record PackageResponse(long id,long storeId,String storeName,String packageCode,String name,long priceCent,String imageUrl,String description,boolean recommended,String status,List<PackageItemResponse> items){}
 public record PackageItemResponse(long dishId,String dishName,int quantity){}
 public record StockAdjustRequest(Integer quantity,String operation,String remark,String idempotencyKey){}
 public record OrderItemRequest(String itemType,Long itemId,Integer quantity){}
 public record CreateReservationRequest(Long storeId,Long tableId,String fulfillmentType,LocalDate reservationDate,LocalTime reservationTime,Integer diningMinutes,Integer guestCount,String contactName,String contactMobile,String deliveryAddress,String remark,List<OrderItemRequest> items,String deliveryArea,String roomNo){
  public CreateReservationRequest(Long storeId,Long tableId,String fulfillmentType,LocalDate reservationDate,LocalTime reservationTime,Integer diningMinutes,Integer guestCount,String contactName,String contactMobile,String deliveryAddress,String remark,List<OrderItemRequest> items){this(storeId,tableId,fulfillmentType,reservationDate,reservationTime,diningMinutes,guestCount,contactName,contactMobile,deliveryAddress,remark,items,null,null);}
 }
 public record ReservationItemResponse(String itemType,Long itemId,String itemCode,String itemName,int quantity,long unitPriceCent,long amountCent){}
 public record ReservationResponse(long id,String reservationNo,Long orderId,String orderNo,long storeId,String storeName,Long tableId,String tableName,String fulfillmentType,LocalDate reservationDate,LocalTime reservationTime,int diningMinutes,int guestCount,String contactName,String contactMobile,String deliveryAddress,String remark,String status,long totalAmountCent,String verificationCode,List<ReservationItemResponse> items,LocalDateTime verifiedAt,LocalDateTime createdAt){}
 public record KitchenTicketResponse(long id,long reservationId,String reservationNo,String storeName,String fulfillmentType,LocalDate reservationDate,LocalTime reservationTime,String station,String status,LocalDateTime pushedAt,LocalDateTime acceptedAt,LocalDateTime readyAt,List<ReservationItemResponse> items){}
 public record KitchenActionRequest(String status,String note){}
 public record VerifyRequest(String code,String deviceNo){}
 public record OverviewResponse(LocalDate startDate,LocalDate endDate,long orderCount,long paidCount,long completedCount,long cancelledCount,long refundCount,long guestCount,long salesAmountCent,long dishSalesAmountCent,long packageSalesAmountCent,long verificationCount){}
 public record DishSalesResponse(String itemType,Long itemId,String itemName,long quantity,long salesAmountCent){}
}
