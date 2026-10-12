package com.iminiapp.backend.hotel.application;

import com.iminiapp.backend.hotel.api.HotelModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class HotelQueryService {
    private final JdbcTemplate jdbc;
    public HotelQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<HotelSummary> hotels() {
        return jdbc.query("""
                SELECT h.id,h.hotel_code,h.name,h.address,h.contact_phone,h.description,h.check_in_time,h.check_out_time,
                       (SELECT image_url FROM bf_hotel_image image WHERE image.hotel_id=h.id AND image.enabled=1
                        ORDER BY image.sort_order,image.id LIMIT 1) cover_url,
                       COUNT(DISTINCT rt.id) room_type_count,
                       MIN(CASE WHEN i.business_date>=CURRENT_DATE AND i.available_quantity>0 THEN i.price_cent END) starting_price
                FROM bf_hotel h
                LEFT JOIN bf_hotel_room_type rt ON rt.hotel_id=h.id AND rt.status='ACTIVE'
                LEFT JOIN bf_inventory i ON i.sku_id=rt.sku_id AND i.time_slot=''
                WHERE h.status='ACTIVE' GROUP BY h.id ORDER BY h.id
                """, (rs,n)->new HotelSummary(rs.getLong("id"),rs.getString("hotel_code"),rs.getString("name"),
                rs.getString("address"),rs.getString("contact_phone"),rs.getString("description"),
                rs.getObject("check_in_time", LocalTime.class),rs.getObject("check_out_time",LocalTime.class),
                rs.getString("cover_url"),rs.getLong("room_type_count"),rs.getObject("starting_price",Long.class)));
    }

    public HotelDetail detail(long hotelId) {
        HotelSummary hotel = hotels().stream().filter(value -> value.id()==hotelId).findFirst()
                .orElseThrow(()->new BusinessException(CommonErrorCode.NOT_FOUND,"酒店不存在或未上架"));
        return new HotelDetail(hotel,images(hotelId,null),roomTypes(hotelId,null,null,1));
    }

    public List<PublicRoomType> roomTypes(long hotelId, LocalDate checkIn, LocalDate checkOut, int roomCount) {
        if(roomCount<1||roomCount>99)throw invalid("roomCount范围为1到99");
        boolean availability=checkIn!=null||checkOut!=null;
        if(availability){
            if(checkIn==null||checkOut==null||checkIn.isBefore(LocalDate.now())||!checkOut.isAfter(checkIn))
                throw invalid("入住和退房日期不合法");
            if(ChronoUnit.DAYS.between(checkIn,checkOut)>30)throw invalid("单次查询最长30晚");
        }
        Integer hotel=jdbc.queryForObject("SELECT COUNT(*) FROM bf_hotel WHERE id=? AND status='ACTIVE'",Integer.class,hotelId);
        if(hotel==null||hotel==0)throw new BusinessException(CommonErrorCode.NOT_FOUND,"酒店不存在或未上架");
        List<RoomTypeBase> types=jdbc.query("""
                SELECT id,sku_id,type_code,name,bed_type,area_sqm,max_guests,total_rooms,description,cover_url,base_price_cent
                FROM bf_hotel_room_type WHERE hotel_id=? AND status='ACTIVE' ORDER BY base_price_cent,id
                """,(rs,n)->new RoomTypeBase(rs.getLong("id"),rs.getLong("sku_id"),rs.getString("type_code"),
                rs.getString("name"),rs.getString("bed_type"),rs.getBigDecimal("area_sqm"),rs.getInt("max_guests"),
                rs.getInt("total_rooms"),rs.getString("description"),rs.getString("cover_url"),rs.getLong("base_price_cent")),hotelId);
        List<PublicRoomType> result=new ArrayList<>();
        for(RoomTypeBase type:types){
            List<NightlyPrice> nights=availability?nightly(type.skuId(),checkIn,checkOut):List.of();
            long expected=availability?ChronoUnit.DAYS.between(checkIn,checkOut):0;
            if(availability&&(nights.size()!=expected||nights.stream().anyMatch(day->day.availableQuantity()<roomCount)))continue;
            Long from=availability?nights.stream().mapToLong(NightlyPrice::priceCent).min().orElse(type.basePrice()):type.basePrice();
            Integer minimum=availability?nights.stream().mapToInt(NightlyPrice::availableQuantity).min().orElse(0):null;
            Long amount=availability?nights.stream().mapToLong(NightlyPrice::priceCent).sum()*roomCount:null;
            result.add(new PublicRoomType(type.id(),type.skuId(),type.code(),type.name(),type.bedType(),type.area(),
                    type.maxGuests(),type.totalRooms(),type.description(),type.coverUrl(),facilities(type.id()),
                    images(null,type.id()),from,minimum,amount,nights));
        }
        return result;
    }

    private List<NightlyPrice> nightly(long skuId,LocalDate start,LocalDate end){
        return jdbc.query("""
                SELECT business_date,price_cent,available_quantity FROM bf_inventory
                WHERE sku_id=? AND time_slot='' AND business_date>=? AND business_date<? ORDER BY business_date
                """,(rs,n)->new NightlyPrice(rs.getObject(1,LocalDate.class),rs.getLong(2),rs.getInt(3)),skuId,start,end);
    }
    private List<FacilityResponse> facilities(long roomTypeId){
        return jdbc.query("SELECT id,facility_code,name,sort_order FROM bf_hotel_facility WHERE room_type_id=? ORDER BY sort_order,id",
                (rs,n)->new FacilityResponse(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4)),roomTypeId);
    }
    private List<ImageResponse> images(Long hotelId,Long roomTypeId){
        String owner=hotelId!=null?"hotel_id=?":"room_type_id=?";Object id=hotelId!=null?hotelId:roomTypeId;
        return jdbc.query("SELECT id,image_url,image_type,sort_order,enabled FROM bf_hotel_image WHERE "+owner+" AND enabled=1 ORDER BY sort_order,id",
                (rs,n)->new ImageResponse(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),true),id);
    }
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private record RoomTypeBase(long id,long skuId,String code,String name,String bedType,BigDecimal area,
                                int maxGuests,int totalRooms,String description,String coverUrl,long basePrice){}
}
