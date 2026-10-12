package com.iminiapp.backend.meeting;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.*;
import com.iminiapp.backend.meeting.application.MeetingManagementService;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class MeetingManagementIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal("meeting-admin", "会议管理员", "ADMIN", List.of("ROLE_ADMIN"));
    @Autowired private MeetingManagementService service;

    @Test
    void 完整维护会议基础资料链路() {
        RoomResponse room = service.createRoom(ADMIN, new RoomUpsertRequest("IT_MEETING_A", "测试会议室A", "综合楼二层", 80, "投影会议室", LocalTime.of(8, 0), LocalTime.of(22, 0), "ACTIVE"));
        EquipmentResponse equipment = service.createEquipment(ADMIN, new EquipmentUpsertRequest(room.id(), "PROJECTOR", "4K投影仪", 2, "含幕布", "ACTIVE"));
        OpeningRuleResponse opening = service.createOpening(ADMIN, new OpeningRuleUpsertRequest(room.id(), 1, LocalTime.of(9, 0), LocalTime.of(18, 0), 30, "ACTIVE"));
        PriceRuleResponse price = service.createPrice(ADMIN, new PriceRuleUpsertRequest(room.id(), "IT_MEETING_WEEKDAY", "工作日下午", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 1, LocalTime.of(13, 0), LocalTime.of(18, 0), 21, 80, 80000L, 10, "ACTIVE"));
        ServiceResponse tea = service.createService(ADMIN, new ServiceUpsertRequest("IT_TEA", "会议茶歇", "TEA_BREAK", "PER_PERSON", 3500L, "每人一份", "ACTIVE"));

        RoomResponse refreshed = service.rooms("ACTIVE").stream().filter(x -> x.id() == room.id()).findFirst().orElseThrow();
        assertEquals(1, refreshed.equipmentCount());
        assertEquals(1, refreshed.openingRuleCount());
        assertEquals(room.id(), equipment.roomId());
        assertEquals(30, opening.slotMinutes());
        assertEquals(80000L, price.priceCent());
        assertEquals("TEA_BREAK", tea.serviceType());
        assertFalse(service.equipment(room.id(), "ACTIVE").isEmpty());
        assertFalse(service.prices(room.id(), "ACTIVE").isEmpty());
    }

    @Test
    void 拒绝重叠时段和收费规则() {
        RoomResponse room = service.createRoom(ADMIN, new RoomUpsertRequest("IT_MEETING_OVERLAP", "测试会议室重叠", "综合楼三层", 60, null, LocalTime.of(8, 0), LocalTime.of(20, 0), "ACTIVE"));
        service.createOpening(ADMIN, new OpeningRuleUpsertRequest(room.id(), 2, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, "ACTIVE"));
        assertThrows(BusinessException.class, () -> service.createOpening(ADMIN, new OpeningRuleUpsertRequest(room.id(), 2, LocalTime.of(11, 30), LocalTime.of(13, 0), 30, "ACTIVE")));
        service.createPrice(ADMIN, new PriceRuleUpsertRequest(room.id(), "IT_MEETING_PRICE_A", "上午价", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 2, LocalTime.of(9, 0), LocalTime.of(12, 0), 1, 30, 50000L, 0, "ACTIVE"));
        assertThrows(BusinessException.class, () -> service.createPrice(ADMIN, new PriceRuleUpsertRequest(room.id(), "IT_MEETING_PRICE_B", "冲突价", LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 1), 2, LocalTime.of(11, 0), LocalTime.of(13, 0), 20, 50, 60000L, 0, "ACTIVE")));
    }

    @Test
    void 拒绝跨会议室设备归属与非法输入() {
        RoomResponse first = service.createRoom(ADMIN, new RoomUpsertRequest("IT_MEETING_FIRST", "第一会议室", "A座", 30, null, LocalTime.of(8, 0), LocalTime.of(20, 0), "ACTIVE"));
        RoomResponse second = service.createRoom(ADMIN, new RoomUpsertRequest("IT_MEETING_SECOND", "第二会议室", "B座", 30, null, LocalTime.of(8, 0), LocalTime.of(20, 0), "ACTIVE"));
        EquipmentResponse equipment = service.createEquipment(ADMIN, new EquipmentUpsertRequest(first.id(), "SCREEN", "屏幕", 1, null, "ACTIVE"));
        service.updateEquipment(ADMIN, equipment.id(), new EquipmentUpsertRequest(second.id(), "SCREEN", "屏幕", 1, null, "ACTIVE"));
        assertEquals(second.id(), service.equipment(second.id(), null).stream().filter(x -> x.id() == equipment.id()).findFirst().orElseThrow().roomId());
        assertThrows(BusinessException.class, () -> service.createEquipment(ADMIN, new EquipmentUpsertRequest(999999L, "BAD", "非法设备", 1, null, "ACTIVE")));
        assertThrows(BusinessException.class, () -> service.createService(ADMIN, new ServiceUpsertRequest("BAD_TYPE", "错误服务", "FOOD", "PER_EVENT", 1L, null, "ACTIVE")));
        assertThrows(BusinessException.class, () -> service.createRoom(ADMIN, new RoomUpsertRequest("BAD_ROOM", "错误会议室", "测试", 0, null, LocalTime.NOON, LocalTime.NOON, "ACTIVE")));
    }
}
