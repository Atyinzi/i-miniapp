package com.iminiapp.backend.meeting;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.BookingResponse;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.CreateBookingRequest;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.*;
import com.iminiapp.backend.meeting.application.MeetingBookingService;
import com.iminiapp.backend.meeting.application.MeetingManagementService;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
class MeetingOccupancyConcurrencyIT {
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "meeting-concurrency-user", "会议并发用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "meeting-concurrency-admin", "会议并发管理员", "ADMIN", List.of("ROLE_ADMIN"));

    @Autowired MeetingBookingService booking;
    @Autowired MeetingManagementService management;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 两个并发请求抢同一会议室时仅一个提交成功() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        LocalDate date = nextMonday();
        RoomResponse room = management.createRoom(ADMIN, new RoomUpsertRequest(
                "IT_CONCURRENT_" + suffix, "并发会议室" + suffix, "会议中心", 50, null,
                LocalTime.of(8, 0), LocalTime.of(20, 0), "ACTIVE"));
        management.createOpening(ADMIN, new OpeningRuleUpsertRequest(
                room.id(), 1, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, "ACTIVE"));
        management.createPrice(ADMIN, new PriceRuleUpsertRequest(
                room.id(), "IT_CONCURRENT_PRICE_" + suffix, "并发会议价", date.minusDays(1), date.plusDays(1),
                1, LocalTime.of(9, 0), LocalTime.of(12, 0), 1, 50, 80000L, 10, "ACTIVE"));

        BookingResponse first = booking.create(USER, "concurrent-a-" + suffix,
                request(room.id(), date, "并发会议A"));
        BookingResponse second = booking.create(USER, "concurrent-b-" + suffix,
                request(room.id(), date, "并发会议B"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (long id : List.of(first.id(), second.id())) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        booking.submit(USER, id);
                        return true;
                    } catch (BusinessException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
            cleanup(room.id(), date, List.of(first.id(), second.id()));
        }
    }

    private CreateBookingRequest request(long roomId, LocalDate date, String subject) {
        return new CreateBookingRequest(roomId, "EXTERNAL", subject, date,
                LocalTime.of(9, 0), LocalTime.of(10, 0), 20,
                "并发测试", "13800000000", null, List.of());
    }

    private LocalDate nextMonday() {
        LocalDate candidate = LocalDate.now().with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        return candidate.isAfter(LocalDate.now()) ? candidate : candidate.plusWeeks(1);
    }

    private void cleanup(long roomId, LocalDate date, List<Long> bookingIds) {
        List<Long> orderIds = jdbc.queryForList("""
                SELECT order_id FROM bf_meeting_reservation WHERE id IN (?,?) AND order_id IS NOT NULL
                """, Long.class, bookingIds.get(0), bookingIds.get(1));
        jdbc.update("DELETE FROM bf_meeting_reservation_status_log WHERE booking_id IN (?,?)",
                bookingIds.get(0), bookingIds.get(1));
        jdbc.update("DELETE FROM bf_meeting_reservation_service WHERE booking_id IN (?,?)",
                bookingIds.get(0), bookingIds.get(1));
        jdbc.update("DELETE FROM bf_meeting_reservation WHERE id IN (?,?)", bookingIds.get(0), bookingIds.get(1));
        for (Long orderId : orderIds) {
            jdbc.update("DELETE FROM bf_order_item WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM bf_order WHERE id=?", orderId);
        }
        jdbc.update("DELETE FROM bf_meeting_room_day_lock WHERE room_id=? AND booking_date=?", roomId, date);
        jdbc.update("DELETE FROM bf_meeting_price_rule WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM bf_meeting_opening_rule WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM bf_meeting_room_equipment WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM bf_meeting_room WHERE id=?", roomId);
    }
}
