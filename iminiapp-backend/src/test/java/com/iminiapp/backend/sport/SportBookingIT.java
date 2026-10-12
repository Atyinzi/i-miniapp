package com.iminiapp.backend.sport;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.application.ExpiredOrderService;
import com.iminiapp.backend.payment.api.PaymentResponse;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.refund.api.RefundApplicationResponse;
import com.iminiapp.backend.refund.application.RefundApplicationService;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.sport.api.SportBookingModels.*;
import com.iminiapp.backend.sport.api.SportMemberModels.*;
import com.iminiapp.backend.sport.api.SportVenueModels.*;
import com.iminiapp.backend.sport.application.SportBookingService;
import com.iminiapp.backend.sport.application.SportMemberService;
import com.iminiapp.backend.sport.application.SportReportService;
import com.iminiapp.backend.sport.application.SportVerificationService;
import com.iminiapp.backend.sport.application.SportVenueService;
import com.iminiapp.backend.verification.api.VerificationTicketResponse;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.task.scheduling.enabled=false"
        })
@Transactional
class SportBookingIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "sport-booking-admin", "体育预订管理员", "ADMIN", List.of("ROLE_ADMIN"));
    private static final AuthenticatedPrincipal USER_A = new AuthenticatedPrincipal(
            "sport-booking-user-a", "预订用户A", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal USER_B = new AuthenticatedPrincipal(
            "sport-booking-user-b", "预订用户B", "USER", List.of("ROLE_USER"));

    @Autowired private SportVenueService venueService;
    @Autowired private SportBookingService bookingService;
    @Autowired private SportMemberService memberService;
    @Autowired private SportVerificationService sportVerificationService;
    @Autowired private SportReportService sportReportService;
    @Autowired private VerificationTicketService verificationTicketService;
    @Autowired private ExpiredOrderService expiredOrderService;
    @Autowired private MockPaymentService paymentService;
    @Autowired private RefundApplicationService refundService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 可订日历应用节假日价格且取消后释放时段() {
        Fixture fixture = fixture("CALENDAR");
        List<AvailableSlotResponse> slots = bookingService.availability(fixture.courtId(), fixture.date());
        AvailableSlotResponse target = slots.stream().filter(v -> v.startTime().equals(LocalTime.of(10, 0))).findFirst().orElseThrow();
        assertEquals(8800L, target.priceCent());
        assertEquals("测试节假日价", target.priceRuleName());
        assertTrue(target.available());

        BookingResponse first = bookingService.create(USER_A, "sport-calendar-order", request(fixture, target));
        BookingResponse duplicate = bookingService.create(USER_A, "sport-calendar-order", request(fixture, target));
        assertEquals(first.orderId(), duplicate.orderId());
        assertEquals("PENDING_PAYMENT", first.orderStatus());
        assertFalse(bookingService.availability(fixture.courtId(), fixture.date()).stream()
                .filter(v -> v.startTime().equals(target.startTime())).findFirst().orElseThrow().available());

        BookingResponse cancelled = bookingService.cancel(USER_A, first.orderId());
        assertEquals("CANCELLED", cancelled.bookingStatus());
        BookingResponse second = bookingService.create(USER_B, "sport-calendar-rebook", request(fixture, target));
        assertNotEquals(first.orderId(), second.orderId());
    }

    @Test
    void 同一场地同一时段只能创建一个待支付订单() {
        Fixture fixture = fixture("OVERBOOK");
        AvailableSlotResponse target = bookingService.availability(fixture.courtId(), fixture.date()).get(0);
        bookingService.create(USER_A, "sport-overbook-a", request(fixture, target));
        assertThrows(BusinessException.class,
                () -> bookingService.create(USER_B, "sport-overbook-b", request(fixture, target)));
    }

    @Test
    void 支付超时自动取消并释放体育时段() {
        Fixture fixture = fixture("EXPIRE");
        AvailableSlotResponse target = bookingService.availability(fixture.courtId(), fixture.date()).get(0);
        BookingResponse booking = bookingService.create(USER_A, "sport-expire-order", request(fixture, target));
        jdbc.update("UPDATE bf_order SET expires_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), booking.orderId());

        assertEquals(1, expiredOrderService.expireOverdue());
        BookingResponse expired = bookingService.detail(USER_A, booking.orderId());
        assertEquals("CANCELLED", expired.orderStatus());
        assertTrue(bookingService.availability(fixture.courtId(), fixture.date()).get(0).available());
    }

    @Test
    void 临时闭场会从可订日历移除且不能覆盖已有订单() {
        Fixture fixture = fixture("CLOSURE");
        AvailableSlotResponse target = bookingService.availability(fixture.courtId(), fixture.date()).get(0);
        BookingResponse booking = bookingService.create(USER_A, "sport-closure-order", request(fixture, target));
        ClosureCreateRequest closure = new ClosureCreateRequest(fixture.courtId(), fixture.date(),
                target.startTime(), target.endTime(), "设备维护");
        assertThrows(BusinessException.class, () -> bookingService.createClosure(ADMIN, closure));
        bookingService.cancel(USER_A, booking.orderId());
        ClosureResponse created = bookingService.createClosure(ADMIN, closure);
        assertEquals("ACTIVE", created.status());
        assertTrue(bookingService.availability(fixture.courtId(), fixture.date()).stream()
                .noneMatch(v -> v.startTime().equals(target.startTime())));
    }

    @Test
    void 模拟支付确认预订且退款成功后释放时段() {
        Fixture fixture = fixture("PAY_REFUND");
        AvailableSlotResponse target = bookingService.availability(fixture.courtId(), fixture.date()).get(0);
        BookingResponse booking = bookingService.create(USER_A, "sport-payment-order", request(fixture, target));

        PaymentResponse paid = paymentService.pay(USER_A, booking.orderId(), "sport-payment-key");
        PaymentResponse duplicate = paymentService.pay(USER_A, booking.orderId(), "sport-payment-key");
        assertEquals(paid.id(), duplicate.id());
        BookingResponse confirmed = bookingService.detail(USER_A, booking.orderId());
        assertEquals("PAID", confirmed.orderStatus());
        assertEquals("CONFIRMED", confirmed.bookingStatus());
        assertNotNull(confirmed.confirmedAt());
        assertTrue(confirmed.statusLogs().stream().anyMatch(log -> "CONFIRMED".equals(log.toStatus())));

        RefundApplicationResponse applied = refundService.apply(USER_A, booking.orderId(),
                "sport-refund-apply", "临时行程调整");
        assertEquals("PENDING_REVIEW", applied.status());
        assertEquals("REFUND_PENDING", bookingService.detail(USER_A, booking.orderId()).bookingStatus());
        RefundApplicationResponse processing = refundService.approve(ADMIN, applied.id(), "同意退款");
        assertEquals("PROCESSING", processing.status());
        assertEquals("REFUNDING", bookingService.detail(USER_A, booking.orderId()).bookingStatus());

        RefundApplicationResponse completed = refundService.callbackFromWechat(
                processing.channelRefundId(), processing.refundNo(), "SUCCESS",
                processing.amountCent(), processing.amountCent(), "channel refund succeeded");
        assertEquals("SUCCESS", completed.status());
        BookingResponse refunded = bookingService.detail(USER_A, booking.orderId());
        assertEquals("REFUNDED", refunded.orderStatus());
        assertEquals("REFUNDED", refunded.bookingStatus());
        assertNotNull(refunded.refundedAt());
        assertTrue(bookingService.availability(fixture.courtId(), fixture.date()).get(0).available());
    }

    @Test
    void 会员折扣余额支付及退款形成完整不可变流水() {
        MemberResponse member = memberService.register(USER_A);
        assertEquals(member.id(), memberService.register(USER_A).id());
        assertEquals("NORMAL", member.levelCode());
        member = memberService.assignLevel(ADMIN, member.id(), new AssignLevelRequest("GOLD"));
        assertEquals(9000, member.sportDiscountBp());

        WalletTransactionResponse recharge = memberService.recharge(USER_A, "sport-member-recharge",
                new RechargeRequest(10_000L, "测试充值"));
        WalletTransactionResponse duplicateRecharge = memberService.recharge(USER_A, "sport-member-recharge",
                new RechargeRequest(10_000L, "重复请求"));
        assertEquals(recharge.id(), duplicateRecharge.id());
        assertEquals(10_000L, memberService.mine(USER_A).balanceCent());

        Fixture fixture = fixture("MEMBER_WALLET");
        AvailableSlotResponse target = bookingService.availability(fixture.courtId(), fixture.date()).get(0);
        BookingResponse booking = bookingService.create(USER_A, "sport-member-booking", request(fixture, target));
        assertEquals(8_800L, booking.originalPriceCent());
        assertEquals(7_920L, booking.priceCent());
        assertEquals(880L, booking.memberDiscountCent());
        assertEquals("GOLD", booking.memberLevelCode());

        PaymentResponse payment = memberService.balancePay(USER_A, booking.orderId(), "sport-balance-pay");
        PaymentResponse duplicatePayment = memberService.balancePay(USER_A, booking.orderId(), "sport-balance-pay");
        assertEquals(payment.id(), duplicatePayment.id());
        assertEquals("BALANCE", payment.channel());
        assertEquals(2_080L, memberService.mine(USER_A).balanceCent());
        assertEquals("CONFIRMED", bookingService.detail(USER_A, booking.orderId()).bookingStatus());

        RefundApplicationResponse applied = refundService.apply(USER_A, booking.orderId(),
                "sport-member-refund", "余额支付退款测试");
        RefundApplicationResponse processing = refundService.approve(ADMIN, applied.id(), "同意退款");
        refundService.callbackFromWechat(processing.channelRefundId(), processing.refundNo(), "SUCCESS",
                processing.amountCent(), processing.amountCent(), "channel refund succeeded");
        assertEquals(10_000L, memberService.mine(USER_A).balanceCent());
        var transactions = memberService.transactions(USER_A, 1, 20).items();
        assertEquals(3, transactions.size());
        assertTrue(transactions.stream().anyMatch(v -> "RECHARGE".equals(v.transactionType()) && v.amountCent() == 10_000L));
        assertTrue(transactions.stream().anyMatch(v -> "CONSUME".equals(v.transactionType()) && v.amountCent() == -7_920L));
        assertTrue(transactions.stream().anyMatch(v -> "REFUND".equals(v.transactionType()) && v.amountCent() == 7_920L));
        var adminMembers = memberService.adminMembers("GOLD", "ACTIVE", 1, 20);
        long memberId = member.id();
        assertTrue(adminMembers.items().stream().anyMatch(v -> v.id() == memberId));
        assertEquals(3, memberService.adminTransactions(memberId, 1, 20).total());
    }

    @Test
    void 体育预订扫码核销后进入使用中并由工作人员完成() {
        Fixture fixture = fixture("VERIFY", LocalDate.now());
        AvailableSlotResponse target = bookingService.availability(fixture.courtId(), fixture.date()).get(0);
        BookingResponse booking = bookingService.create(USER_A, "sport-verify-booking", request(fixture, target));
        paymentService.pay(USER_A, booking.orderId(), "sport-verify-payment");

        List<VerificationTicketResponse> tickets = verificationTicketService.listOwnedTickets(USER_A, booking.orderId());
        assertEquals(1, tickets.size());
        assertNotNull(tickets.get(0).code());
        VerificationTicketResponse used = verificationTicketService.consume(ADMIN, tickets.get(0).code(), "GATE-01");
        assertEquals("USED", used.status());
        BookingResponse inUse = bookingService.detail(USER_A, booking.orderId());
        assertEquals("IN_USE", inUse.bookingStatus());
        assertEquals("PAID", inUse.orderStatus());
        assertNotNull(inUse.checkedInAt());
        assertEquals(used.verificationId(), inUse.verificationId());
        assertThrows(BusinessException.class,
                () -> verificationTicketService.consume(ADMIN, tickets.get(0).code(), "GATE-02"));

        var records = sportVerificationService.records(null, fixture.date(), 1, 20);
        assertEquals(1, records.total());
        assertEquals("GATE-01", records.items().get(0).deviceNo());
        var completed = sportVerificationService.complete(ADMIN, booking.orderId());
        assertEquals("COMPLETED", completed.bookingStatus());
        assertEquals("COMPLETED", completed.orderStatus());
        assertNotNull(completed.completedAt());
        assertEquals(completed, sportVerificationService.complete(ADMIN, booking.orderId()));
        assertTrue(bookingService.detail(USER_A, booking.orderId()).statusLogs().stream()
                .anyMatch(log -> "COMPLETED".equals(log.toStatus())));
    }

    @Test
    void 体育运营报表统计收入核销会员消费与利用率并导出CSV() {
        memberService.register(USER_A);
        Fixture fixture = fixture("REPORT", LocalDate.now());
        List<AvailableSlotResponse> slots = bookingService.availability(fixture.courtId(), fixture.date());
        BookingResponse first = bookingService.create(USER_A, "sport-report-a", request(fixture, slots.get(0)));
        BookingResponse second = bookingService.create(USER_B, "sport-report-b", request(fixture, slots.get(1)));
        paymentService.pay(USER_A, first.orderId(), "sport-report-pay-a");
        paymentService.pay(USER_B, second.orderId(), "sport-report-pay-b");
        VerificationTicketResponse ticket = verificationTicketService.listOwnedTickets(USER_A, first.orderId()).get(0);
        verificationTicketService.consume(ADMIN, ticket.code(), "REPORT-GATE");
        sportVerificationService.complete(ADMIN, first.orderId());

        var overview = sportReportService.overview(fixture.date(), fixture.date(), fixture.venueId());
        assertEquals(2, overview.bookingCount());
        assertEquals(2, overview.paidBookingCount());
        assertEquals(1, overview.completedBookingCount());
        assertEquals(17_600L, overview.grossRevenueCent());
        assertEquals(0, overview.refundAmountCent());
        assertEquals(1, overview.verificationCount());
        assertEquals(0, overview.verificationRatePercent().compareTo(new java.math.BigDecimal("50.00")));
        assertEquals(1, overview.memberBookingCount());
        assertEquals(8_800L, overview.memberConsumptionCent());
        assertEquals(2, overview.capacitySlots());
        assertEquals(2, overview.occupiedSlots());
        assertEquals(0, overview.utilizationRatePercent().compareTo(new java.math.BigDecimal("100.00")));

        var court = sportReportService.utilization(fixture.date(), fixture.date(), fixture.venueId(), "COURT");
        assertEquals(1, court.size());
        assertEquals(2, court.get(0).capacitySlots());
        assertEquals(2, court.get(0).occupiedSlots());
        var details = sportReportService.usageDetails(fixture.date(), fixture.date(), fixture.venueId(), null, 1, 20);
        assertEquals(2, details.total());
        assertTrue(details.items().stream().anyMatch(v -> "REPORT-GATE".equals(v.verificationDeviceNo())));
        String csv = new String(sportReportService.exportUsageCsv(fixture.date(), fixture.date(), fixture.venueId(), null),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF订单号"));
        assertTrue(csv.contains(first.orderNo()));
        assertTrue(csv.contains(second.orderNo()));
    }

    private Fixture fixture(String suffix) {
        return fixture(suffix, LocalDate.now().plusDays(7));
    }

    private Fixture fixture(String suffix, LocalDate date) {
        VenueResponse venue = venueService.createVenue(ADMIN, new VenueUpsertRequest(
                "IT_SPORT_" + suffix, "测试体育馆" + suffix, "测试地址", null, null,
                LocalTime.of(8, 0), LocalTime.of(22, 0), "ACTIVE"));
        CourtTypeResponse type = venueService.createCourtType(ADMIN, new CourtTypeUpsertRequest(
                venue.id(), "TYPE_" + suffix, "羽毛球场", null, 6000L, "ACTIVE"));
        CourtResponse court = venueService.createCourt(ADMIN, new CourtUpsertRequest(
                venue.id(), type.id(), "COURT_" + suffix, "测试1号场", null, 4, "ACTIVE"));
        venueService.createOpeningRule(ADMIN, new OpeningRuleUpsertRequest(
                court.id(), date.getDayOfWeek().getValue(), LocalTime.of(10, 0), LocalTime.of(12, 0), 60, "ACTIVE"));
        venueService.createPriceRule(ADMIN, new PriceRuleUpsertRequest(
                venue.id(), type.id(), court.id(), "PRICE_" + suffix, "测试节假日价", "HOLIDAY",
                date, date, null, null, null, 8800L, 100, "ACTIVE"));
        return new Fixture(venue.id(), court.id(), date);
    }

    private CreateBookingRequest request(Fixture fixture, AvailableSlotResponse slot) {
        return new CreateBookingRequest(fixture.courtId(), fixture.date(), slot.startTime(), slot.endTime(), "集成测试预订");
    }
    private record Fixture(long venueId, long courtId, LocalDate date) {}
}
