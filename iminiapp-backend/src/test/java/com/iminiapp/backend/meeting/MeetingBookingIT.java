package com.iminiapp.backend.meeting;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.*;
import com.iminiapp.backend.meeting.api.MeetingApprovalModels.ReplaceRulesRequest;
import com.iminiapp.backend.meeting.api.MeetingApprovalModels.RuleStepRequest;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.*;
import com.iminiapp.backend.meeting.application.MeetingBookingService;
import com.iminiapp.backend.meeting.application.MeetingAvailabilityService;
import com.iminiapp.backend.meeting.application.MeetingApprovalService;
import com.iminiapp.backend.meeting.application.MeetingManagementService;
import com.iminiapp.backend.order.application.OrderApplicationService;
import com.iminiapp.backend.order.application.ExpiredOrderService;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class MeetingBookingIT {
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "meeting-booking-user", "会议预订用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal OTHER = new AuthenticatedPrincipal(
            "meeting-booking-other", "其他会议用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "meeting-booking-admin", "会议管理员", "ADMIN", List.of("ROLE_ADMIN"));

    @Autowired MeetingBookingService booking;
    @Autowired MeetingAvailabilityService availability;
    @Autowired MeetingApprovalService approval;
    @Autowired MeetingManagementService management;
    @Autowired OrderApplicationService orders;
    @Autowired ExpiredOrderService expiredOrders;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 外部会议草稿组合服务并提交统一待支付订单() {
        Fixture f = fixture("EXTERNAL", 1, 60000, 2500);
        CreateBookingRequest request = request(f, "EXTERNAL", List.of(new ServiceSelection(f.serviceId(), 20)));

        BookingResponse draft = booking.create(USER, "booking-external", request);
        assertEquals("DRAFT", draft.status());
        assertNull(draft.orderId());
        assertEquals(110000, draft.price().totalAmountCent());
        assertEquals(50000, draft.price().serviceAmountCent());
        assertEquals(1, draft.services().size());

        BookingResponse submitted = booking.submit(USER, draft.id());
        assertEquals("PENDING_PAYMENT", submitted.status());
        assertNotNull(submitted.orderId());
        assertNotNull(submitted.orderNo());
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject(
                "SELECT status FROM bf_order WHERE id=?", String.class, submitted.orderId()));
        assertEquals("MEETING_BOOKING", jdbc.queryForObject(
                "SELECT order_type FROM bf_order WHERE id=?", String.class, submitted.orderId()));
        assertEquals(110000L, jdbc.queryForObject(
                "SELECT payable_amount_cent FROM bf_order WHERE id=?", Long.class, submitted.orderId()));
    }

    @Test
    void 内部会议提交后进入待审批且重复提交保持幂等() {
        Fixture f = fixture("INTERNAL", 2, 70000, 1000);
        long approverId = jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",
                Long.class, ADMIN.databaseOpenId());
        approval.replaceRules(ADMIN, new ReplaceRulesRequest(
                List.of(new RuleStepRequest(approverId, "部门负责人审批"))));
        BookingResponse draft = booking.create(USER, "booking-internal",
                request(f, "INTERNAL", List.of(new ServiceSelection(f.serviceId(), 5))));

        BookingResponse first = booking.submit(USER, draft.id());
        BookingResponse second = booking.submit(USER, draft.id());

        assertEquals("PENDING_APPROVAL", first.status());
        assertEquals(first.orderId(), second.orderId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM bf_order WHERE id=?", Integer.class, first.orderId()));
    }

    @Test
    void 创建幂等且提交时重新计算配套服务价格() {
        Fixture f = fixture("REPRICE", 3, 50000, 1000);
        CreateBookingRequest request = request(f, "EXTERNAL", List.of(new ServiceSelection(f.serviceId(), 10)));
        BookingResponse first = booking.create(USER, "booking-reprice", request);
        BookingResponse duplicate = booking.create(USER, "booking-reprice", request);
        assertEquals(first.id(), duplicate.id());
        assertEquals(60000, first.price().totalAmountCent());

        ServiceResponse current = management.services(null, null).stream()
                .filter(s -> s.id() == f.serviceId()).findFirst().orElseThrow();
        management.updateService(ADMIN, f.serviceId(), new ServiceUpsertRequest(
                current.serviceCode(), current.name(), current.serviceType(), current.pricingUnit(),
                2000L, current.description(), "ACTIVE"));

        BookingResponse submitted = booking.submit(USER, first.id());
        assertEquals(70000, submitted.price().totalAmountCent());
        assertEquals(20000, submitted.price().serviceAmountCent());
        assertEquals(2000, submitted.services().get(0).unitPriceCent());
    }

    @Test
    void 草稿和待处理订单均可取消且取消操作幂等() {
        Fixture draftFixture = fixture("CANCEL_DRAFT", 4, 40000, 1000);
        BookingResponse draft = booking.create(USER, "booking-cancel-draft",
                request(draftFixture, "EXTERNAL", List.of()));
        assertEquals("CANCELLED", booking.cancel(USER, draft.id()).status());

        Fixture orderFixture = fixture("CANCEL_ORDER", 5, 45000, 1000);
        BookingResponse submitted = booking.submit(USER, booking.create(USER, "booking-cancel-order",
                request(orderFixture, "EXTERNAL", List.of())).id());
        orders.cancel(USER, submitted.orderId());
        BookingResponse cancelled = booking.detail(USER, submitted.id());
        BookingResponse duplicate = booking.cancel(USER, submitted.id());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(cancelled.id(), duplicate.id());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM bf_order WHERE id=?", String.class, submitted.orderId()));
    }

    @Test
    void 拒绝不可订时段和跨用户访问() {
        Fixture f = fixture("SECURITY", 6, 55000, 1000);
        BookingResponse draft = booking.create(USER, "booking-security",
                request(f, "EXTERNAL", List.of()));
        assertThrows(BusinessException.class, () -> booking.detail(OTHER, draft.id()));

        CreateBookingRequest unavailable = new CreateBookingRequest(f.roomId(), "EXTERNAL", "越界会议",
                f.date(), LocalTime.of(7, 0), LocalTime.of(7, 30), 10,
                "张三", "13800000000", null, List.of());
        assertThrows(BusinessException.class,
                () -> booking.create(USER, "booking-unavailable", unavailable));
    }

    @Test
    void 提交后占用重叠时段且取消后立即释放() {
        Fixture f = fixture("OCCUPANCY", 7, 65000, 1000);
        CreateBookingRequest firstRequest = request(f, "EXTERNAL", LocalTime.of(9, 0),
                LocalTime.of(10, 0), List.of());
        CreateBookingRequest secondRequest = request(f, "EXTERNAL", LocalTime.of(9, 30),
                LocalTime.of(10, 30), List.of());
        BookingResponse first = booking.create(USER, "booking-occupancy-first", firstRequest);
        BookingResponse second = booking.create(USER, "booking-occupancy-second", secondRequest);

        BookingResponse occupied = booking.submit(USER, first.id());
        assertNotNull(occupied.occupiedAt());
        assertEquals(MeetingAvailabilityService.TIME_SLOT_OCCUPIED,
                availability.availability(f.roomId(), f.date(), 10,
                        LocalTime.of(9, 30), LocalTime.of(10, 0)).unavailableCode());
        assertThrows(BusinessException.class, () -> booking.submit(USER, second.id()));

        BookingResponse released = booking.cancel(USER, first.id());
        assertNotNull(released.releasedAt());
        BookingResponse submittedSecond = booking.submit(USER, second.id());
        assertEquals("PENDING_PAYMENT", submittedSecond.status());
        assertNotNull(submittedSecond.occupiedAt());
    }

    @Test
    void 待支付订单超时后自动释放会议室时段() {
        Fixture f = fixture("EXPIRE_RELEASE", 1, 68000, 1000);
        BookingResponse submitted = booking.submit(USER, booking.create(USER, "booking-expire-release",
                request(f, "EXTERNAL", List.of())).id());
        jdbc.update("UPDATE bf_order SET expires_at=? WHERE id=?",
                LocalDateTime.now().minusMinutes(1), submitted.orderId());

        assertTrue(expiredOrders.expireOverdue() >= 1);
        BookingResponse cancelled = booking.detail(USER, submitted.id());
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.releasedAt());
        assertTrue(availability.availability(f.roomId(), f.date(), 20,
                LocalTime.of(9, 0), LocalTime.of(10, 0)).available());
    }

    private Fixture fixture(String suffix, int day, long venuePrice, long servicePrice) {
        LocalDate date = nextDate(day);
        RoomResponse room = management.createRoom(ADMIN, new RoomUpsertRequest(
                "IT_BOOK_" + suffix, "预订会议室" + suffix, "会议中心", 100, null,
                LocalTime.of(8, 0), LocalTime.of(20, 0), "ACTIVE"));
        management.createOpening(ADMIN, new OpeningRuleUpsertRequest(
                room.id(), day, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, "ACTIVE"));
        management.createPrice(ADMIN, new PriceRuleUpsertRequest(
                room.id(), "IT_BOOK_PRICE_" + suffix, "会议价格" + suffix,
                date.minusDays(1), date.plusDays(1), day, LocalTime.of(9, 0), LocalTime.of(12, 0),
                1, 100, venuePrice, 10, "ACTIVE"));
        ServiceResponse service = management.createService(ADMIN, new ServiceUpsertRequest(
                "IT_BOOK_SERVICE_" + suffix, "会议茶歇" + suffix, "TEA_BREAK", "PER_PERSON",
                servicePrice, null, "ACTIVE"));
        return new Fixture(room.id(), service.id(), date);
    }

    private CreateBookingRequest request(Fixture f, String type, List<ServiceSelection> services) {
        return request(f, type, LocalTime.of(9, 0), LocalTime.of(10, 0), services);
    }

    private CreateBookingRequest request(Fixture f, String type, LocalTime start, LocalTime end,
                                         List<ServiceSelection> services) {
        return new CreateBookingRequest(f.roomId(), type, "年度工作会议", f.date(), start, end, 20,
                "张三", "13800000000", "请提前布置", services);
    }

    private LocalDate nextDate(int dayOfWeek) {
        LocalDate candidate = LocalDate.now().with(
                TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(dayOfWeek)));
        return candidate.isAfter(LocalDate.now()) ? candidate : candidate.plusWeeks(1);
    }

    private record Fixture(long roomId, long serviceId, LocalDate date) {}
}
