package com.iminiapp.backend.hotel;

import com.iminiapp.backend.hotel.api.HotelBookingModels.*;
import com.iminiapp.backend.hotel.api.HotelModels.*;
import com.iminiapp.backend.hotel.application.HotelBookingService;
import com.iminiapp.backend.hotel.application.HotelManagementService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.refund.api.RefundApplicationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class HotelBookingWorkflowIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "hotel-booking-admin", "住宿管理员", "ADMIN", List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "hotel-booking-user", "住宿用户", "USER", List.of("ROLE_USER"));
    @Autowired HotelManagementService management;
    @Autowired HotelBookingService booking;
    @Autowired MockPaymentService payment;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 可报价锁定逐晚库存支付并申请退款() {
        Setup setup=setup(8);
        CreateBookingRequest request=request(setup.roomType().id(),setup.start(),setup.start().plusDays(2),3);

        QuoteResponse quote=booking.quote(new QuoteRequest(setup.roomType().id(),setup.start(),setup.start().plusDays(2),3));
        BookingResponse created=booking.create(USER,"hotel-create-"+UUID.randomUUID(),request);

        assertEquals(38800L*3*2,quote.totalAmountCent());
        assertEquals("PENDING_PAYMENT",created.orderStatus());
        assertEquals(2,created.guests().size());
        assertTrue(created.guests().get(0).maskedIdNo().contains("********"));
        assertEquals(List.of(7,7),management.calendar(setup.roomType().id(),setup.start(),setup.start().plusDays(1))
                .stream().map(InventoryDayResponse::availableQuantity).toList());
        assertEquals(2,booking.alerts("OPEN").stream().filter(a->a.orderId()!=null&&a.orderId()==created.orderId()).count());

        payment.pay(USER,created.orderId(),"hotel-pay-"+UUID.randomUUID());
        BookingResponse paid=booking.detail(USER,created.reservationId());
        RefundApplicationResponse refund=booking.applyRefund(USER,created.reservationId(),
                "hotel-refund-"+UUID.randomUUID(),"行程调整");

        assertEquals("PAID",paid.orderStatus());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id
                WHERE i.order_id=?
                """, Integer.class, created.orderId()));
        assertEquals(paid.paidAmountCent(),refund.amountCent());
        assertEquals("PENDING_REVIEW",refund.status());
    }

    @Test
    void 待支付订单可原子改订并取消释放库存() {
        Setup setup=setup(2);
        BookingResponse original=booking.create(USER,"hotel-original-"+UUID.randomUUID(),
                request(setup.roomType().id(),setup.start(),setup.start().plusDays(2),1));
        LocalDate replacementStart=setup.start().plusDays(2);
        ModifyBookingRequest modify=new ModifyBookingRequest(setup.roomType().id(),replacementStart,
                replacementStart.plusDays(2),1,2,"李四","13900139000",LocalTime.of(18,0),
                "安静楼层","改订测试",guests());

        BookingResponse replacement=booking.modify(USER,original.reservationId(),"modify-"+UUID.randomUUID(),modify);
        BookingResponse old=booking.detail(USER,original.reservationId());

        assertEquals("CANCELLED",old.orderStatus());
        assertEquals(replacement.reservationId(),old.supersededByReservationId());
        assertEquals(original.reservationId(),replacement.previousReservationId());
        assertEquals(replacementStart,replacement.checkInDate());

        BookingResponse cancelled=booking.cancel(USER,replacement.reservationId());
        assertEquals("CANCELLED",cancelled.orderStatus());
        assertTrue(management.calendar(setup.roomType().id(),setup.start(),setup.start().plusDays(4))
                .stream().allMatch(day->day.availableQuantity()==10));
    }

    private Setup setup(int lowStockThreshold){
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
        HotelResponse hotel=management.createHotel(ADMIN,new HotelUpsertRequest("IT_HOTEL_"+suffix,"预订测试酒店",
                "测试路1号","0472-1234567",null,LocalTime.of(14,0),LocalTime.NOON,"ACTIVE",List.of()));
        RoomTypeResponse roomType=management.createRoomType(ADMIN,new RoomTypeUpsertRequest(hotel.id(),"TWIN_"+suffix,
                "测试双床房","双床",new BigDecimal("36"),2,10,lowStockThreshold,38800L,null,null,"ACTIVE",
                List.of(new FacilityInput("WIFI","无线网络",1)),List.of()));
        LocalDate start=LocalDate.now().plusDays(20);
        management.generateCalendar(ADMIN,new CalendarGenerateRequest(roomType.id(),start,start.plusDays(5),10));
        return new Setup(roomType,start);
    }
    private CreateBookingRequest request(long roomTypeId,LocalDate start,LocalDate end,int roomCount){
        return new CreateBookingRequest(roomTypeId,start,end,roomCount,2,"李四","13900139000",LocalTime.of(18,0),
                "安静楼层","住宿集成测试",guests());
    }
    private List<GuestInput> guests(){return List.of(
            new GuestInput("李四","13900139000","ID_CARD","110101199001011234",1),
            new GuestInput("王五","13700137000","ID_CARD","110101199001011235",1));}
    private record Setup(RoomTypeResponse roomType,LocalDate start){}
}
