package com.iminiapp.backend.meeting;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingAvailabilityModels.AvailabilityResponse;
import com.iminiapp.backend.meeting.api.MeetingAvailabilityModels.RequestedQuoteResponse;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.OpeningRuleUpsertRequest;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.PriceRuleUpsertRequest;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.RoomResponse;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.RoomUpsertRequest;
import com.iminiapp.backend.meeting.application.MeetingAvailabilityService;
import com.iminiapp.backend.meeting.application.MeetingManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static com.iminiapp.backend.meeting.application.MeetingAvailabilityService.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class MeetingAvailabilityIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "meeting-quote-admin", "会议报价管理员", "ADMIN", List.of("ROLE_ADMIN"));
    @Autowired private MeetingManagementService management;
    @Autowired private MeetingAvailabilityService availability;

    @Test
    void 生成可订时段并按优先级返回动态报价快照() {
        LocalDate date = nextDate(1);
        RoomResponse room = room("QUOTE_OK", 80, "ACTIVE");
        opening(room.id(), 1, LocalTime.of(9, 0), LocalTime.of(12, 0), 30);
        price(room.id(), "QUOTE_BASE", date, 1, LocalTime.of(9, 0), LocalTime.of(12, 0), 1, 80, 50000, 1);
        price(room.id(), "QUOTE_PRIORITY", date, 1, LocalTime.of(9, 0), LocalTime.of(10, 0), 20, 40, 80000, 20);

        AvailabilityResponse result = availability.availability(room.id(), date, 30,
                LocalTime.of(9, 0), LocalTime.of(9, 30));

        assertTrue(result.available());
        assertEquals(6, result.slots().size());
        assertTrue(result.slots().stream().allMatch(s -> s.available() && s.price() != null));
        assertEquals("IT_QUOTE_PRIORITY", result.requestedQuote().price().ruleCode());
        assertEquals(20, result.requestedQuote().price().priority());
        assertEquals(80000, result.requestedQuote().venuePriceCent());
    }

    @Test
    void 容量不足时明确返回不可订原因() {
        LocalDate date = nextDate(2);
        RoomResponse room = room("QUOTE_CAPACITY", 10, "ACTIVE");
        opening(room.id(), 2, LocalTime.of(9, 0), LocalTime.of(10, 0), 30);

        AvailabilityResponse result = availability.availability(room.id(), date, 11,
                LocalTime.of(9, 0), LocalTime.of(9, 30));

        assertFalse(result.available());
        assertEquals(CAPACITY_EXCEEDED, result.unavailableCode());
        assertEquals(CAPACITY_EXCEEDED, result.requestedQuote().unavailableCode());
        assertTrue(result.slots().stream().noneMatch(s -> s.available()));
    }

    @Test
    void 非开放日期与营业时间外请求均返回精确原因() {
        LocalDate monday = nextDate(1);
        RoomResponse room = room("QUOTE_CLOSED", 30, "ACTIVE");
        opening(room.id(), 1, LocalTime.of(9, 0), LocalTime.of(12, 0), 30);

        AvailabilityResponse closedDate = availability.availability(room.id(), monday.plusDays(1), 10,
                LocalTime.of(9, 0), LocalTime.of(9, 30));
        AvailabilityResponse outsideBusiness = availability.availability(room.id(), monday, 10,
                LocalTime.of(7, 30), LocalTime.of(8, 0));
        AvailabilityResponse outsideOpening = availability.availability(room.id(), monday, 10,
                LocalTime.of(12, 0), LocalTime.of(12, 30));

        assertEquals(DATE_NOT_OPEN, closedDate.unavailableCode());
        assertEquals(OUTSIDE_BUSINESS_HOURS, outsideBusiness.requestedQuote().unavailableCode());
        assertEquals(OUTSIDE_OPENING_RULE, outsideOpening.requestedQuote().unavailableCode());
    }

    @Test
    void 边界时段可报价且未对齐时段被拒绝() {
        LocalDate date = nextDate(3);
        RoomResponse room = room("QUOTE_BOUNDARY", 50, "ACTIVE");
        opening(room.id(), 3, LocalTime.of(9, 0), LocalTime.of(12, 0), 30);
        price(room.id(), "QUOTE_ALL_DAY", date, 3, LocalTime.of(9, 0), LocalTime.of(12, 0),
                1, 50, 100000, 5);

        RequestedQuoteResponse exactBoundary = availability.availability(room.id(), date, 20,
                LocalTime.of(9, 0), LocalTime.of(12, 0)).requestedQuote();
        RequestedQuoteResponse misaligned = availability.availability(room.id(), date, 20,
                LocalTime.of(9, 15), LocalTime.of(9, 45)).requestedQuote();

        assertTrue(exactBoundary.available());
        assertEquals(180, exactBoundary.durationMinutes());
        assertFalse(misaligned.available());
        assertEquals(SLOT_ALIGNMENT_REQUIRED, misaligned.unavailableCode());
    }

    @Test
    void 没有匹配价格时不可下单并返回明确原因() {
        LocalDate date = nextDate(4);
        RoomResponse room = room("QUOTE_NO_PRICE", 30, "ACTIVE");
        opening(room.id(), 4, LocalTime.of(9, 0), LocalTime.of(10, 0), 30);

        AvailabilityResponse result = availability.availability(room.id(), date, 10,
                LocalTime.of(9, 0), LocalTime.of(9, 30));

        assertFalse(result.available());
        assertEquals(PRICE_RULE_NOT_FOUND, result.unavailableCode());
        assertEquals(PRICE_RULE_NOT_FOUND, result.requestedQuote().unavailableCode());
        assertEquals(0, result.requestedQuote().venuePriceCent());
    }

    private RoomResponse room(String suffix, int capacity, String status) {
        return management.createRoom(ADMIN, new RoomUpsertRequest("IT_" + suffix, "测试会议室" + suffix,
                "综合楼", capacity, null, LocalTime.of(8, 0), LocalTime.of(20, 0), status));
    }

    private void opening(long roomId, int day, LocalTime start, LocalTime end, int slotMinutes) {
        management.createOpening(ADMIN, new OpeningRuleUpsertRequest(roomId, day, start, end, slotMinutes, "ACTIVE"));
    }

    private void price(long roomId, String code, LocalDate date, int day, LocalTime start, LocalTime end,
                       int min, int max, long priceCent, int priority) {
        management.createPrice(ADMIN, new PriceRuleUpsertRequest(roomId, "IT_" + code, code,
                date.minusDays(1), date.plusDays(1), day, start, end, min, max, priceCent, priority, "ACTIVE"));
    }

    private LocalDate nextDate(int dayOfWeek) {
        LocalDate candidate = LocalDate.now().with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(dayOfWeek)));
        return candidate.isAfter(LocalDate.now()) ? candidate : candidate.plusWeeks(1);
    }
}
