package com.iminiapp.backend.hotel.application;

import com.iminiapp.backend.order.extension.OrderBusinessContext;
import com.iminiapp.backend.order.extension.OrderBusinessExtension;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.security.SensitiveDataCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HotelOrderExtension implements OrderBusinessExtension {
    private final JdbcTemplate jdbc;
    private final SensitiveDataCipher cipher;
    public HotelOrderExtension(JdbcTemplate jdbc, SensitiveDataCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override public String resourceType() { return "HOTEL_ROOM"; }

    @Override
    public void validate(OrderBusinessContext context) {
        Booking booking = booking(context.businessData());
        if (booking.checkIn().isBefore(LocalDate.now()) || !booking.checkOut().isAfter(booking.checkIn()))
            throw invalid("入住和退房日期不合法");
        if (context.serviceDate() == null || context.serviceDate().isBefore(booking.checkIn())
                || !context.serviceDate().isBefore(booking.checkOut())) throw invalid("订单服务日期不在住宿期间内");
        if (booking.primary() != context.serviceDate().equals(booking.checkIn()))
            throw invalid("住宿订单首晚标记不正确");
        if (booking.roomCount() != context.quantity()) throw invalid("房间数与订单明细数量不一致");
        if (booking.guestCount() != booking.guests().size()) throw invalid("入住人数与入住人资料数量不一致");
        if (!StringUtils.hasText(booking.reservationNo()) || booking.reservationNo().length() > 40)
            throw invalid("住宿预订编号不合法");
        if (!StringUtils.hasText(booking.contactName()) || booking.contactName().length() > 64
                || !StringUtils.hasText(booking.contactMobile()) || booking.contactMobile().length() > 32)
            throw invalid("联系人姓名或手机号不合法");
        validateGuests(booking);
        Integer validSku = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_hotel_room_type rt JOIN bf_hotel h ON h.id=rt.hotel_id
                WHERE rt.sku_id=? AND rt.status='ACTIVE' AND h.status='ACTIVE'
                """, Integer.class, context.skuId());
        if (validSku == null || validSku == 0) throw invalid("房型不存在或未上架");
    }

    @Override
    public void save(long orderId, long orderItemId, OrderBusinessContext context) {
        Booking booking = booking(context.businessData());
        if (!booking.primary()) return;
        RoomType roomType = jdbc.queryForObject("SELECT hotel_id,id FROM bf_hotel_room_type WHERE sku_id=?",
                (rs,n)->new RoomType(rs.getLong(1),rs.getLong(2)),context.skuId());
        jdbc.update("""
                INSERT INTO bf_hotel_reservation
                (reservation_no,order_id,primary_order_item_id,hotel_id,room_type_id,check_in_date,check_out_date,
                 room_count,guest_count,contact_name_cipher,contact_mobile_cipher,estimated_arrival_time,
                 special_requests,previous_reservation_id,contact_name_hash,contact_mobile_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, booking.reservationNo(),orderId,orderItemId,roomType.hotelId(),roomType.roomTypeId(),
                booking.checkIn(),booking.checkOut(),booking.roomCount(),booking.guestCount(),
                cipher.encrypt(booking.contactName()),cipher.encrypt(booking.contactMobile()),booking.arrivalTime(),
                booking.specialRequests(),booking.previousReservationId(),cipher.searchHash(booking.contactName()),
                cipher.searchHash(booking.contactMobile()));
        long reservationId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        for(Guest guest:booking.guests()){
            jdbc.update("""
                    INSERT INTO bf_hotel_reservation_guest
                    (reservation_id,room_index,name_cipher,mobile_cipher,id_type,id_no_cipher,id_no_hash)
                    VALUES (?,?,?,?,?,?,?)
                    """,reservationId,guest.roomIndex(),cipher.encrypt(guest.name()),cipher.encrypt(guest.mobile()),
                    guest.idType(),cipher.encrypt(guest.idNo()),cipher.searchHash(guest.idNo()));
            jdbc.update("""
                    INSERT INTO bf_order_person
                    (order_item_id,person_type,name_cipher,mobile_cipher,id_type,id_no_cipher,id_no_hash)
                    VALUES (?,'HOTEL_GUEST',?,?,?,?,?)
                    """,orderItemId,cipher.encrypt(guest.name()),cipher.encrypt(guest.mobile()),guest.idType(),
                    cipher.encrypt(guest.idNo()),cipher.searchHash(guest.idNo()));
        }
    }

    private Booking booking(Map<String,Object> data){
        if(data==null||data.isEmpty())throw invalid("住宿订单必须提供businessData");
        return new Booking(text(data.get("reservationNo")),date(data.get("checkInDate"),"checkInDate"),
                date(data.get("checkOutDate"),"checkOutDate"),integer(data.get("roomCount"),"roomCount"),
                integer(data.get("guestCount"),"guestCount"),text(data.get("contactName")),
                text(data.get("contactMobile")),text(data.get("estimatedArrivalTime")),
                text(data.get("specialRequests")),longValue(data.get("previousReservationId")),
                !Boolean.FALSE.equals(data.get("primaryNight")),guests(data.get("guests")));
    }
    private List<Guest> guests(Object value){
        if(!(value instanceof List<?> list)||list.isEmpty())throw invalid("入住人资料不能为空");
        List<Guest> result=new ArrayList<>();
        for(Object item:list){if(!(item instanceof Map<?,?> map))throw invalid("入住人资料格式错误");
            result.add(new Guest(text(map.get("name")),text(map.get("mobile")),text(map.get("idType")),
                    text(map.get("idNo")),integer(map.get("roomIndex"),"roomIndex")));}
        return result;
    }
    private void validateGuests(Booking booking){
        Set<String> ids=new HashSet<>();
        for(Guest guest:booking.guests()){
            if(!StringUtils.hasText(guest.name())||guest.name().length()>64)throw invalid("入住人姓名不合法");
            if(StringUtils.hasText(guest.mobile())&&guest.mobile().length()>32)throw invalid("入住人手机号不合法");
            if(!StringUtils.hasText(guest.idType())||guest.idType().length()>20)throw invalid("证件类型不合法");
            if(!StringUtils.hasText(guest.idNo())||guest.idNo().length()<6||guest.idNo().length()>30)throw invalid("证件号码不合法");
            if(guest.roomIndex()<1||guest.roomIndex()>booking.roomCount())throw invalid("入住人分配的房间序号不合法");
            if(!ids.add(guest.idType()+":"+guest.idNo()))throw invalid("入住人证件不能重复");
        }
    }
    private LocalDate date(Object value,String field){try{return LocalDate.parse(text(value));}catch(DateTimeParseException|NullPointerException ex){throw invalid(field+"必须为yyyy-MM-dd日期");}}
    private int integer(Object value,String field){if(!(value instanceof Number number)||number.doubleValue()!=Math.rint(number.doubleValue())||number.intValue()<1)throw invalid(field+"必须为正整数");return number.intValue();}
    private Long longValue(Object value){if(value==null)return null;if(!(value instanceof Number number)||number.longValue()<1)throw invalid("previousReservationId不合法");return number.longValue();}
    private String text(Object value){return value==null?null:String.valueOf(value).trim();}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private record Booking(String reservationNo,LocalDate checkIn,LocalDate checkOut,int roomCount,int guestCount,
                           String contactName,String contactMobile,String arrivalTime,String specialRequests,
                           Long previousReservationId,boolean primary,List<Guest> guests){}
    private record Guest(String name,String mobile,String idType,String idNo,int roomIndex){}
    private record RoomType(long hotelId,long roomTypeId){}
}
