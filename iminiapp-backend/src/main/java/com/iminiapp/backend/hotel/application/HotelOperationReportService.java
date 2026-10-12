package com.iminiapp.backend.hotel.application;

import com.iminiapp.backend.hotel.api.HotelOperationModels.HotelOperationReport;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class HotelOperationReportService {
    private final JdbcTemplate jdbc;
    public HotelOperationReportService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public HotelOperationReport report(Long hotelId,LocalDate start,LocalDate end){
        LocalDate actualEnd=end==null?LocalDate.now():end;LocalDate actualStart=start==null?actualEnd.minusDays(29):start;
        if(actualEnd.isBefore(actualStart)||ChronoUnit.DAYS.between(actualStart,actualEnd)>366)
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"报表日期范围不合法或超过367天");
        String hotelFilter=hotelId==null?"":" AND rt.hotel_id="+hotelId;
        long available=value("""
                SELECT COALESCE(SUM(i.total_quantity),0) FROM bf_inventory i
                JOIN bf_hotel_room_type rt ON rt.sku_id=i.sku_id
                WHERE i.time_slot='' AND i.business_date BETWEEN ? AND ?
                """+hotelFilter,actualStart,actualEnd);
        long sold=value("""
                SELECT COALESCE(SUM(oi.quantity),0) FROM bf_order_item oi JOIN bf_order o ON o.id=oi.order_id
                JOIN bf_hotel_room_type rt ON rt.sku_id=oi.sku_id
                WHERE oi.resource_type='HOTEL_ROOM' AND oi.service_date BETWEEN ? AND ?
                  AND o.status IN ('PAID','READY','APPROVED','COMPLETED','REFUNDING')
                """+hotelFilter,actualStart,actualEnd);
        long roomRevenue=value("""
                SELECT COALESCE(SUM(oi.amount_cent),0) FROM bf_order_item oi JOIN bf_order o ON o.id=oi.order_id
                JOIN bf_hotel_room_type rt ON rt.sku_id=oi.sku_id
                WHERE oi.resource_type='HOTEL_ROOM' AND oi.service_date BETWEEN ? AND ?
                  AND o.status IN ('PAID','READY','APPROVED','COMPLETED','REFUNDING')
                """+hotelFilter,actualStart,actualEnd);
        long occupied=value("""
                SELECT COALESCE(SUM(oi.quantity),0) FROM bf_order_item oi JOIN bf_order o ON o.id=oi.order_id
                JOIN bf_hotel_reservation r ON r.order_id=o.id JOIN bf_hotel_room_type rt ON rt.id=r.room_type_id
                WHERE oi.resource_type='HOTEL_ROOM' AND oi.service_date BETWEEN ? AND ?
                  AND r.stay_status IN ('CHECKED_IN','CHECKED_OUT')
                """+hotelFilter,actualStart,actualEnd);
        String reservationHotel=hotelId==null?"":" AND r.hotel_id="+hotelId;
        long extra=value("""
                SELECT COALESCE(SUM(s.extra_charge_cent),0) FROM bf_hotel_settlement s
                JOIN bf_hotel_reservation r ON r.id=s.reservation_id
                WHERE DATE(s.settled_at) BETWEEN ? AND ? AND s.status='COMPLETED'
                """+reservationHotel,actualStart,actualEnd);
        long checkIns=value("SELECT COUNT(*) FROM bf_hotel_reservation r WHERE DATE(r.actual_check_in_at) BETWEEN ? AND ?"+reservationHotel,actualStart,actualEnd);
        long checkOuts=value("SELECT COUNT(*) FROM bf_hotel_reservation r WHERE DATE(r.actual_check_out_at) BETWEEN ? AND ?"+reservationHotel,actualStart,actualEnd);
        long noShows=value("SELECT COUNT(*) FROM bf_hotel_reservation r WHERE DATE(r.no_show_at) BETWEEN ? AND ?"+reservationHotel,actualStart,actualEnd);
        double occupancy=available==0?0:Math.round(sold*10000.0/available)/100.0;
        long adr=sold==0?0:roomRevenue/sold;long revPar=available==0?0:roomRevenue/available;
        return new HotelOperationReport(actualStart,actualEnd,available,sold,occupied,occupancy,roomRevenue,extra,adr,revPar,checkIns,checkOuts,noShows);
    }
    private long value(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
}
