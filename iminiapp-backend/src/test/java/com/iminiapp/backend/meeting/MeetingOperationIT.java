package com.iminiapp.backend.meeting;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.*;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.*;
import com.iminiapp.backend.meeting.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class MeetingOperationIT {
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal("meeting-operation-user","运营测试用户","USER",List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal("meeting-operation-admin","运营管理员","ADMIN",List.of("ROLE_ADMIN"));
    @Autowired MeetingManagementService management;
    @Autowired MeetingBookingService booking;
    @Autowired MeetingOperationService operation;

    @Test
    void 会议台账利用率服务销售与导出保持一致() {
        LocalDate date=LocalDate.now().plusDays(1);
        int day=date.getDayOfWeek().getValue();
        RoomResponse room=management.createRoom(ADMIN,new RoomUpsertRequest("IT_OP_ROOM","运营会议室","会议中心",50,null,LocalTime.of(8,0),LocalTime.of(20,0),"ACTIVE"));
        management.createOpening(ADMIN,new OpeningRuleUpsertRequest(room.id(),day,LocalTime.of(9,0),LocalTime.of(18,0),30,"ACTIVE"));
        management.createPrice(ADMIN,new PriceRuleUpsertRequest(room.id(),"IT_OP_PRICE","运营价格",date,date,day,LocalTime.of(9,0),LocalTime.of(18,0),1,50,50000L,10,"ACTIVE"));
        ServiceResponse tea=management.createService(ADMIN,new ServiceUpsertRequest("IT_OP_TEA","运营茶歇","TEA_BREAK","PER_PERSON",1000L,null,"ACTIVE"));
        BookingResponse submitted=booking.submit(USER,booking.create(USER,"meeting-operation-request",new CreateBookingRequest(room.id(),"EXTERNAL","运营统计会议",date,LocalTime.of(9,0),LocalTime.of(10,0),10,"张三","13800000000",null,List.of(new ServiceSelection(tea.id(),10)))).id());

        assertEquals(submitted.id(),operation.bookings(date,date,room.id(),null,null,"运营统计").get(0).id());
        var overview=operation.overview(date,date);
        assertEquals(1,overview.bookingCount());
        assertEquals(60000,overview.quotedAmountCent());
        assertEquals(10000,overview.serviceAmountCent());
        assertEquals(540,overview.capacityMinutes());
        assertEquals(60,overview.occupiedMinutes());
        assertEquals(1,operation.serviceSales(date,date).stream().filter(v->v.serviceId()==tea.id()).count());
        assertTrue(new String(operation.exportCsv(date,date,room.id(),null,null,null),java.nio.charset.StandardCharsets.UTF_8).contains(submitted.bookingNo()));
    }
}
