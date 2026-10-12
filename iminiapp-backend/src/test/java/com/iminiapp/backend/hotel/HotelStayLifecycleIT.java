package com.iminiapp.backend.hotel;

import com.iminiapp.backend.hotel.api.HotelBookingModels.*;
import com.iminiapp.backend.hotel.api.HotelModels.*;
import com.iminiapp.backend.hotel.api.HotelOperationModels.*;
import com.iminiapp.backend.hotel.application.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.payment.application.MockPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class HotelStayLifecycleIT {
    private static final AuthenticatedPrincipal ADMIN=new AuthenticatedPrincipal("hotel-front-admin","酒店前台","ADMIN",List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER=new AuthenticatedPrincipal("hotel-stay-user","入住用户","USER",List.of("ROLE_USER"));
    @Autowired HotelManagementService management;
    @Autowired HotelBookingService booking;
    @Autowired HotelCheckinCredentialService credential;
    @Autowired HotelOperationService operation;
    @Autowired HotelOperationReportService report;
    @Autowired MockPaymentService payment;

    @Test
    void 完成入住换房续住退房结算和清洁全流程(){
        Setup setup=setup();LocalDate today=LocalDate.now();
        BookingResponse reserved=booking.create(USER,key("stay-create"),new CreateBookingRequest(setup.roomType().id(),
                today,today.plusDays(1),1,1,"赵六","13600136000",LocalTime.of(15,0),null,"入住测试",
                List.of(new GuestInput("赵六","13600136000","ID_CARD","110101199001011236",1))));
        payment.pay(USER,reserved.orderId(),key("stay-pay"));
        CheckInCredentialResponse checkInCode=credential.issue(USER,reserved.reservationId());

        AdminStayDetail checkedIn=operation.checkInByCode(ADMIN,key("check-in"),
                new CheckInByCodeRequest(checkInCode.checkInCode(),List.of(setup.firstRoom().id()),false,"前台扫码入住"));
        assertEquals("CHECKED_IN",checkedIn.stay().stayStatus());
        assertEquals("OCCUPIED",room(setup.firstRoom().id()).status());

        AdminStayDetail changed=operation.changeRoom(ADMIN,reserved.reservationId(),key("change-room"),
                new ChangeRoomRequest(setup.firstRoom().id(),setup.secondRoom().id(),"客人申请安静房间"));
        assertEquals(2,changed.roomAssignments().size());
        assertEquals("DIRTY",room(setup.firstRoom().id()).status());
        assertEquals("OCCUPIED",room(setup.secondRoom().id()).status());

        ExtendStayResponse extended=operation.extendStay(ADMIN,reserved.reservationId(),key("extend"),
                new ExtendStayRequest(today.plusDays(2),"续住一晚"));
        assertEquals(1,extended.addedNights());
        assertEquals(38800,extended.additionalAmountCent());

        CheckoutQuoteResponse quote=operation.checkoutQuote(reserved.reservationId(),
                List.of(new ChargeInput("MINIBAR","迷你吧饮品",1,1000L)));
        assertEquals(39800,quote.additionalDueCent());
        SettlementResponse settlement=operation.checkout(ADMIN,reserved.reservationId(),key("checkout"),
                new CheckoutRequest(List.of(new ChargeInput("MINIBAR","迷你吧饮品",1,1000L)),39800L,0L,"WECHAT","正常退房"));
        assertEquals("COMPLETED",settlement.status());
        assertEquals(78600,settlement.finalAmountCent());
        assertEquals("DIRTY",room(setup.secondRoom().id()).status());

        assertEquals("VACANT",operation.completeCleaning(ADMIN,setup.firstRoom().id()).status());
        assertEquals("VACANT",operation.completeCleaning(ADMIN,setup.secondRoom().id()).status());
        HotelOperationReport reportValue=report.report(setup.hotel().id(),today,today.plusDays(2));
        assertTrue(reportValue.soldRoomNights()>=2);
        assertTrue(reportValue.roomRevenueCent()>=77600);
        assertEquals(1,reportValue.checkInCount());
        assertEquals(1,reportValue.checkOutCount());
    }

    @Test
    void 支持按联系人和订单状态查询后台住宿订单(){
        Setup setup=setup();LocalDate today=LocalDate.now();
        BookingResponse reserved=booking.create(USER,key("search-create"),new CreateBookingRequest(setup.roomType().id(),
                today,today.plusDays(1),1,1,"孙七","13500135000",LocalTime.of(16,0),null,null,
                List.of(new GuestInput("孙七","13500135000","ID_CARD","110101199001011237",1))));
        payment.pay(USER,reserved.orderId(),key("search-pay"));

        assertEquals(1,operation.bookings("PAID","13500135000",setup.hotel().id(),today,today).stream()
                .filter(value->value.reservationId()==reserved.reservationId()).count());
        assertTrue(operation.warnings().stream().anyMatch(value->value.reservationId()==reserved.reservationId()
                && "ARRIVAL_TODAY".equals(value.warningType())));
    }

    private Setup setup(){String suffix=UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
        HotelResponse hotel=management.createHotel(ADMIN,new HotelUpsertRequest("IT_STAY_"+suffix,"履约测试酒店","测试路2号",null,null,LocalTime.of(14,0),LocalTime.NOON,"ACTIVE",List.of()));
        RoomTypeResponse type=management.createRoomType(ADMIN,new RoomTypeUpsertRequest(hotel.id(),"KING_"+suffix,"商务大床房","大床",new BigDecimal("32"),2,2,0,38800L,null,null,"ACTIVE",List.of(),List.of()));
        RoomResponse first=management.createRoom(ADMIN,new RoomUpsertRequest(hotel.id(),type.id(),"80"+suffix.substring(0,2),"8","VACANT",null));
        RoomResponse second=management.createRoom(ADMIN,new RoomUpsertRequest(hotel.id(),type.id(),"81"+suffix.substring(0,2),"8","VACANT",null));
        LocalDate today=LocalDate.now();management.generateCalendar(ADMIN,new CalendarGenerateRequest(type.id(),today,today.plusDays(5),2));
        return new Setup(hotel,type,first,second);}
    private RoomResponse room(long id){return management.rooms(null,null,null).stream().filter(value->value.id()==id).findFirst().orElseThrow();}
    private String key(String prefix){return prefix+"-"+UUID.randomUUID();}
    private record Setup(HotelResponse hotel,RoomTypeResponse roomType,RoomResponse firstRoom,RoomResponse secondRoom){}
}
