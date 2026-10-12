package com.iminiapp.backend.hotel.application;

import com.iminiapp.backend.hotel.api.HotelBookingModels.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.api.CreateOrderRequest;
import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.order.application.OrderApplicationService;
import com.iminiapp.backend.refund.api.RefundApplicationResponse;
import com.iminiapp.backend.refund.application.RefundApplicationService;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.security.SensitiveDataCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class HotelBookingService {
    private final JdbcTemplate jdbc;
    private final OrderApplicationService orderService;
    private final RefundApplicationService refundService;
    private final SensitiveDataCipher cipher;

    public HotelBookingService(JdbcTemplate jdbc, OrderApplicationService orderService,
                               RefundApplicationService refundService, SensitiveDataCipher cipher) {
        this.jdbc = jdbc;
        this.orderService = orderService;
        this.refundService = refundService;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public QuoteResponse quote(QuoteRequest request) {
        QuoteValues value = validateQuote(request);
        RoomType room = activeRoomType(value.roomTypeId());
        List<NightQuote> nights = nightly(room, value.checkIn(), value.checkOut(), value.roomCount());
        int expected = (int) ChronoUnit.DAYS.between(value.checkIn(), value.checkOut());
        if (nights.size() != expected) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,
                "住宿期间存在尚未开放销售的日期");
        int minimum = nights.stream().mapToInt(NightQuote::availableQuantity).min().orElse(0);
        if (minimum < value.roomCount()) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,
                "房型库存不足，住宿期间最少仅剩 " + minimum + " 间");
        long total = nights.stream().mapToLong(NightQuote::amountCent).sum();
        return new QuoteResponse(room.hotelId(),room.hotelName(),room.roomTypeId(),room.skuId(),room.roomTypeName(),
                value.checkIn(),value.checkOut(),expected,value.roomCount(),total,minimum,
                nights.stream().anyMatch(NightQuote::lowStock),nights);
    }

    @Transactional
    public BookingResponse create(AuthenticatedPrincipal principal, String idempotencyKey, CreateBookingRequest request) {
        return createInternal(principal, normalizeKey(idempotencyKey), request, null);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> mine(AuthenticatedPrincipal principal) {
        long userId=requireUser(principal);
        List<Long> ids=jdbc.queryForList("""
                SELECT r.id FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id
                WHERE o.user_id=? ORDER BY r.id DESC
                """,Long.class,userId);
        return ids.stream().map(id->detailById(userId,id)).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse detail(AuthenticatedPrincipal principal,long reservationId) {
        return detailById(requireUser(principal),reservationId);
    }

    @Transactional
    public BookingResponse cancel(AuthenticatedPrincipal principal,long reservationId) {
        long userId=requireUser(principal);
        ReservationLock reservation=lockOwned(userId,reservationId);
        orderService.cancel(principal,reservation.orderId());
        resolveRecoveredAlerts(reservation.roomTypeId(),reservation.checkIn(),reservation.checkOut());
        return detailById(userId,reservationId);
    }

    @Transactional
    public BookingResponse modify(AuthenticatedPrincipal principal,long reservationId,String idempotencyKey,
                                  ModifyBookingRequest request) {
        long userId=requireUser(principal);
        ReservationLock current=lockOwned(userId,reservationId);
        if(current.supersededBy()!=null)return detailById(userId,current.supersededBy());
        if(!"PENDING_PAYMENT".equals(current.orderStatus()))
            throw new BusinessException(CommonErrorCode.ORDER_CONFLICT,"当前仅支持待支付住宿订单改订，已支付订单请申请退款后重新预订");
        CreateBookingRequest replacement=new CreateBookingRequest(request.roomTypeId(),request.checkInDate(),
                request.checkOutDate(),request.roomCount(),request.guestCount(),request.contactName(),
                request.contactMobile(),request.estimatedArrivalTime(),request.specialRequests(),request.remark(),request.guests());
        orderService.cancel(principal,current.orderId());
        BookingResponse created=createInternal(principal,modifyKey(reservationId,idempotencyKey),replacement,reservationId);
        jdbc.update("UPDATE bf_hotel_reservation SET superseded_by_reservation_id=? WHERE id=? AND superseded_by_reservation_id IS NULL",
                created.reservationId(),reservationId);
        resolveRecoveredAlerts(current.roomTypeId(),current.checkIn(),current.checkOut());
        return created;
    }

    @Transactional
    public RefundApplicationResponse applyRefund(AuthenticatedPrincipal principal,long reservationId,
                                                  String idempotencyKey,String reason) {
        ReservationLock reservation=lockOwned(requireUser(principal),reservationId);
        return refundService.apply(principal,reservation.orderId(),idempotencyKey,reason);
    }

    public List<InventoryAlertResponse> alerts(String status) {
        reconcileRecoveredAlerts();
        String normalized=StringUtils.hasText(status)?status.trim().toUpperCase(Locale.ROOT):null;
        if(normalized!=null&&!List.of("OPEN","RESOLVED").contains(normalized))throw invalid("预警状态仅支持OPEN或RESOLVED");
        return jdbc.query("""
                SELECT a.*,rt.name room_type_name FROM bf_hotel_inventory_alert a
                JOIN bf_hotel_room_type rt ON rt.id=a.room_type_id
                """+(normalized==null?"":" WHERE a.status=?")+" ORDER BY a.id DESC",
                (rs,n)->new InventoryAlertResponse(rs.getLong("id"),rs.getLong("room_type_id"),
                        rs.getString("room_type_name"),rs.getObject("business_date",LocalDate.class),
                        rs.getObject("order_id",Long.class),rs.getString("alert_type"),
                        rs.getInt("available_quantity"),rs.getInt("threshold_quantity"),rs.getString("status"),
                        rs.getString("message"),rs.getObject("created_at",LocalDateTime.class),
                        rs.getObject("resolved_at",LocalDateTime.class)),normalized==null?new Object[0]:new Object[]{normalized});
    }

    @Transactional
    public InventoryAlertResponse resolveAlert(long alertId) {
        int changed=jdbc.update("UPDATE bf_hotel_inventory_alert SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE id=? AND status='OPEN'",alertId);
        if(changed==0){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM bf_hotel_inventory_alert WHERE id=?",Integer.class,alertId);if(count==null||count==0)throw notFound("库存预警不存在");}
        return alerts(null).stream().filter(value->value.id()==alertId).findFirst().orElseThrow(()->notFound("库存预警不存在"));
    }

    private BookingResponse createInternal(AuthenticatedPrincipal principal,String key,CreateBookingRequest request,
                                           Long previousReservationId) {
        validateCreate(request);
        QuoteResponse quoted=quote(new QuoteRequest(request.roomTypeId(),request.checkInDate(),request.checkOutDate(),request.roomCount()));
        String reservationNo=reservationNo();
        List<CreateOrderRequest.Item> items=new ArrayList<>();
        for(LocalDate day=request.checkInDate();day.isBefore(request.checkOutDate());day=day.plusDays(1)){
            Map<String,Object> data=businessData(reservationNo,request,previousReservationId,day.equals(request.checkInDate()));
            items.add(new CreateOrderRequest.Item(quoted.skuId(),request.roomCount(),day,"",data));
        }
        OrderResponse order=orderService.create(principal,key,new CreateOrderRequest("HOTEL_ROOM",items,request.remark()));
        List<Long> reservations=jdbc.queryForList("SELECT id FROM bf_hotel_reservation WHERE order_id=?",Long.class,order.id());
        if(reservations.isEmpty())throw new BusinessException(CommonErrorCode.CONFLICT,"该幂等键已被其他类型订单使用");
        long reservationId=reservations.get(0);
        createLowStockAlerts(reservationId,order.id());
        return detailById(requireUser(principal),reservationId);
    }

    private Map<String,Object> businessData(String reservationNo,CreateBookingRequest request,Long previous,boolean primary){
        List<Map<String,Object>> guests=request.guests().stream().map(guest->{Map<String,Object> value=new LinkedHashMap<>();
            value.put("name",guest.name());value.put("mobile",guest.mobile());value.put("idType",guest.idType());
            value.put("idNo",guest.idNo());value.put("roomIndex",guest.roomIndex()==null?1:guest.roomIndex());return value;}).toList();
        Map<String,Object> data=new LinkedHashMap<>();data.put("reservationNo",reservationNo);
        data.put("checkInDate",request.checkInDate().toString());data.put("checkOutDate",request.checkOutDate().toString());
        data.put("roomCount",request.roomCount());data.put("guestCount",request.guestCount());
        data.put("contactName",request.contactName());data.put("contactMobile",request.contactMobile());
        data.put("estimatedArrivalTime",request.estimatedArrivalTime()==null?null:request.estimatedArrivalTime().toString());
        data.put("specialRequests",request.specialRequests());data.put("previousReservationId",previous);
        data.put("primaryNight",primary);data.put("guests",guests);return data;
    }

    private void validateCreate(CreateBookingRequest request){
        if(request==null||request.guestCount()==null||request.guests()==null)throw invalid("预订信息和入住人资料不能为空");
        validateQuote(new QuoteRequest(request.roomTypeId(),request.checkInDate(),request.checkOutDate(),request.roomCount()));
        if(request.guestCount()<1||request.guestCount()>99||request.guests().size()!=request.guestCount())throw invalid("入住人数与入住人资料数量不一致");
        if(!StringUtils.hasText(request.contactName())||request.contactName().trim().length()>64)throw invalid("联系人姓名不能为空且最长64字符");
        if(!StringUtils.hasText(request.contactMobile())||request.contactMobile().trim().length()>32)throw invalid("联系人手机号不能为空且最长32字符");
        if(StringUtils.hasText(request.specialRequests())&&request.specialRequests().trim().length()>1000)throw invalid("特殊要求最长1000字符");
        if(StringUtils.hasText(request.remark())&&request.remark().trim().length()>500)throw invalid("备注最长500字符");
    }
    private QuoteValues validateQuote(QuoteRequest request){
        if(request==null||request.roomTypeId()==null||request.checkInDate()==null||request.checkOutDate()==null||request.roomCount()==null)
            throw invalid("roomTypeId、入住退房日期和房间数不能为空");
        if(request.checkInDate().isBefore(LocalDate.now())||!request.checkOutDate().isAfter(request.checkInDate()))throw invalid("入住和退房日期不合法");
        long nights=ChronoUnit.DAYS.between(request.checkInDate(),request.checkOutDate());
        if(nights>20)throw invalid("单次住宿最多20晚");
        if(request.roomCount()<1||request.roomCount()>99)throw invalid("房间数范围为1到99");
        return new QuoteValues(request.roomTypeId(),request.checkInDate(),request.checkOutDate(),request.roomCount());
    }
    private RoomType activeRoomType(long id){
        return jdbc.query("""
                SELECT rt.id,rt.sku_id,rt.name,h.id hotel_id,h.name hotel_name,rt.low_stock_threshold
                FROM bf_hotel_room_type rt JOIN bf_hotel h ON h.id=rt.hotel_id
                WHERE rt.id=? AND rt.status='ACTIVE' AND h.status='ACTIVE'
                """,(rs,n)->new RoomType(rs.getLong("hotel_id"),rs.getString("hotel_name"),rs.getLong("id"),
                rs.getLong("sku_id"),rs.getString("name"),rs.getInt("low_stock_threshold")),id).stream().findFirst()
                .orElseThrow(()->notFound("房型不存在或未上架"));
    }
    private List<NightQuote> nightly(RoomType room,LocalDate start,LocalDate end,int roomCount){
        return jdbc.query("""
                SELECT business_date,price_cent,available_quantity FROM bf_inventory
                WHERE sku_id=? AND time_slot='' AND business_date>=? AND business_date<? ORDER BY business_date
                """,(rs,n)->{int available=rs.getInt("available_quantity");long price=rs.getLong("price_cent");
            return new NightQuote(rs.getObject("business_date",LocalDate.class),price,available,price*roomCount,
                    available-roomCount<=room.lowStockThreshold());},room.skuId(),start,end);
    }

    private BookingResponse detailById(long userId,long reservationId){
        BookingHead head=jdbc.query("""
                SELECT r.*,o.order_no,o.status order_status,o.total_amount_cent,o.payable_amount_cent,o.paid_amount_cent,
                       o.expires_at,h.name hotel_name,rt.name room_type_name,rt.sku_id
                FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id
                JOIN bf_hotel h ON h.id=r.hotel_id JOIN bf_hotel_room_type rt ON rt.id=r.room_type_id
                WHERE r.id=? AND o.user_id=?
                """,(rs,n)->new BookingHead(rs.getLong("id"),rs.getString("reservation_no"),rs.getLong("order_id"),
                rs.getString("order_no"),rs.getString("order_status"),rs.getLong("hotel_id"),rs.getString("hotel_name"),
                rs.getLong("room_type_id"),rs.getLong("sku_id"),rs.getString("room_type_name"),
                rs.getObject("check_in_date",LocalDate.class),rs.getObject("check_out_date",LocalDate.class),
                rs.getInt("room_count"),rs.getInt("guest_count"),cipher.decrypt(rs.getBytes("contact_name_cipher")),
                cipher.decrypt(rs.getBytes("contact_mobile_cipher")),rs.getObject("estimated_arrival_time",java.time.LocalTime.class),
                rs.getString("special_requests"),rs.getLong("total_amount_cent"),rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),rs.getObject("expires_at",LocalDateTime.class),
                rs.getObject("previous_reservation_id",Long.class),rs.getObject("superseded_by_reservation_id",Long.class),
                rs.getObject("created_at",LocalDateTime.class)),reservationId,userId).stream().findFirst()
                .orElseThrow(()->notFound("住宿订单不存在或无权访问"));
        List<GuestResponse> guests=jdbc.query("""
                SELECT id,name_cipher,mobile_cipher,id_type,id_no_cipher,room_index FROM bf_hotel_reservation_guest
                WHERE reservation_id=? ORDER BY room_index,id
                """,(rs,n)->new GuestResponse(rs.getLong("id"),cipher.decrypt(rs.getBytes("name_cipher")),
                cipher.decrypt(rs.getBytes("mobile_cipher")),rs.getString("id_type"),mask(cipher.decrypt(rs.getBytes("id_no_cipher"))),
                rs.getInt("room_index")),reservationId);
        List<NightQuote> nights=jdbc.query("""
                SELECT i.service_date,i.unit_price_cent,inv.available_quantity,rt.low_stock_threshold,i.quantity
                FROM bf_order_item i JOIN bf_hotel_room_type rt ON rt.sku_id=i.sku_id
                LEFT JOIN bf_inventory inv ON inv.sku_id=i.sku_id AND inv.business_date=i.service_date AND inv.time_slot=''
                WHERE i.order_id=? ORDER BY i.service_date
                """,(rs,n)->{int available=rs.getInt("available_quantity"),quantity=rs.getInt("quantity");long price=rs.getLong("unit_price_cent");
            return new NightQuote(rs.getObject("service_date",LocalDate.class),price,available,price*quantity,
                    available<=rs.getInt("low_stock_threshold"));},head.orderId());
        return new BookingResponse(head.id(),head.reservationNo(),head.orderId(),head.orderNo(),head.orderStatus(),
                head.hotelId(),head.hotelName(),head.roomTypeId(),head.skuId(),head.roomTypeName(),head.checkIn(),head.checkOut(),
                (int)ChronoUnit.DAYS.between(head.checkIn(),head.checkOut()),head.roomCount(),head.guestCount(),head.contactName(),
                head.contactMobile(),head.arrival(),head.specialRequests(),head.total(),head.payable(),head.paid(),head.expiresAt(),
                head.previous(),head.supersededBy(),guests,nights,head.createdAt());
    }

    private ReservationLock lockOwned(long userId,long reservationId){
        return jdbc.query("""
                SELECT r.order_id,r.room_type_id,r.check_in_date,r.check_out_date,r.superseded_by_reservation_id,o.status
                FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id
                WHERE r.id=? AND o.user_id=? FOR UPDATE
                """,(rs,n)->new ReservationLock(rs.getLong(1),rs.getLong(2),rs.getObject(3,LocalDate.class),
                rs.getObject(4,LocalDate.class),rs.getObject(5,Long.class),rs.getString(6)),reservationId,userId)
                .stream().findFirst().orElseThrow(()->notFound("住宿订单不存在或无权访问"));
    }
    private void createLowStockAlerts(long reservationId,long orderId){
        List<AlertCandidate> candidates=jdbc.query("""
                SELECT r.room_type_id,i.business_date,i.available_quantity,rt.low_stock_threshold,rt.name
                FROM bf_hotel_reservation r JOIN bf_hotel_room_type rt ON rt.id=r.room_type_id
                JOIN bf_inventory i ON i.sku_id=rt.sku_id AND i.business_date>=r.check_in_date AND i.business_date<r.check_out_date AND i.time_slot=''
                WHERE r.id=? AND i.available_quantity<=rt.low_stock_threshold
                """,(rs,n)->new AlertCandidate(rs.getLong(1),rs.getObject(2,LocalDate.class),rs.getInt(3),
                rs.getInt(4),rs.getString(5)),reservationId);
        for(AlertCandidate candidate:candidates){
            String alertType=candidate.available()==0?"SOLD_OUT_RISK":"LOW_STOCK";
            String message=candidate.roomTypeName()+"在"+candidate.date()+"仅剩"+candidate.available()+"间，请关注超售风险";
            jdbc.update("""
                    UPDATE bf_hotel_inventory_alert SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP
                    WHERE room_type_id=? AND business_date=? AND status='OPEN' AND alert_type<>?
                    """,candidate.roomTypeId(),candidate.date(),alertType);
            jdbc.update("""
                    INSERT INTO bf_hotel_inventory_alert
                    (room_type_id,business_date,order_id,alert_type,available_quantity,threshold_quantity,status,message)
                    VALUES (?,?,?,?,? ,?,'OPEN',?)
                    ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),available_quantity=VALUES(available_quantity),
                    threshold_quantity=VALUES(threshold_quantity),message=VALUES(message),created_at=CURRENT_TIMESTAMP
                    """,candidate.roomTypeId(),candidate.date(),orderId,alertType,candidate.available(),candidate.threshold(),message);
        }
    }
    private void resolveRecoveredAlerts(long roomTypeId,LocalDate start,LocalDate end){
        jdbc.update("""
                UPDATE bf_hotel_inventory_alert a JOIN bf_hotel_room_type rt ON rt.id=a.room_type_id
                JOIN bf_inventory i ON i.sku_id=rt.sku_id AND i.business_date=a.business_date AND i.time_slot=''
                SET a.status='RESOLVED',a.resolved_at=CURRENT_TIMESTAMP
                WHERE a.room_type_id=? AND a.business_date>=? AND a.business_date<? AND a.status='OPEN'
                  AND i.available_quantity>rt.low_stock_threshold
                """,roomTypeId,start,end);
    }
    @Scheduled(fixedDelayString = "${app.hotel.alert-reconcile-ms:60000}", initialDelay = 45000)
    @Transactional
    public void reconcileRecoveredAlerts(){
        jdbc.update("""
                UPDATE bf_hotel_inventory_alert a JOIN bf_hotel_room_type rt ON rt.id=a.room_type_id
                JOIN bf_inventory i ON i.sku_id=rt.sku_id AND i.business_date=a.business_date AND i.time_slot=''
                SET a.status='RESOLVED',a.resolved_at=CURRENT_TIMESTAMP
                WHERE a.status='OPEN' AND i.available_quantity>rt.low_stock_threshold
                """);
    }
    private long requireUser(AuthenticatedPrincipal principal){List<Long> ids=jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,principal.databaseOpenId());if(ids.isEmpty())throw notFound("当前用户尚无住宿订单");return ids.get(0);}
    private String normalizeKey(String key){if(!StringUtils.hasText(key)||key.trim().length()>64)throw invalid("Idempotency-Key不能为空且最长64字符");return key.trim();}
    private String modifyKey(long reservationId,String key){String raw="HM"+reservationId+"_"+normalizeKey(key);return raw.length()<=64?raw:"HM"+reservationId+"_"+Integer.toUnsignedString(raw.hashCode(),36);}
    private String reservationNo(){return "HR"+LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+UUID.randomUUID().toString().replace("-","").substring(0,6).toUpperCase(Locale.ROOT);}
    private String mask(String value){if(value==null||value.length()<8)return "******";return value.substring(0,3)+"********"+value.substring(value.length()-4);}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private BusinessException notFound(String message){return new BusinessException(CommonErrorCode.NOT_FOUND,message);}
    private record QuoteValues(long roomTypeId,LocalDate checkIn,LocalDate checkOut,int roomCount){}
    private record RoomType(long hotelId,String hotelName,long roomTypeId,long skuId,String roomTypeName,int lowStockThreshold){}
    private record ReservationLock(long orderId,long roomTypeId,LocalDate checkIn,LocalDate checkOut,Long supersededBy,String orderStatus){}
    private record BookingHead(long id,String reservationNo,long orderId,String orderNo,String orderStatus,long hotelId,
                               String hotelName,long roomTypeId,long skuId,String roomTypeName,LocalDate checkIn,
                               LocalDate checkOut,int roomCount,int guestCount,String contactName,String contactMobile,
                               java.time.LocalTime arrival,String specialRequests,long total,long payable,long paid,
                               LocalDateTime expiresAt,Long previous,Long supersededBy,LocalDateTime createdAt){}
    private record AlertCandidate(long roomTypeId,LocalDate date,int available,int threshold,String roomTypeName){}
}
