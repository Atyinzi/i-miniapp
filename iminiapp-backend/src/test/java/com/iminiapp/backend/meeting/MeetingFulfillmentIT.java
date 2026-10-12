package com.iminiapp.backend.meeting;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingApprovalModels.*;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.*;
import com.iminiapp.backend.meeting.api.MeetingFulfillmentModels.*;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.*;
import com.iminiapp.backend.meeting.application.*;
import com.iminiapp.backend.payment.application.MockPaymentService;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.verification.api.VerificationTicketResponse;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class MeetingFulfillmentIT {
    private static final AuthenticatedPrincipal USER = new AuthenticatedPrincipal(
            "meeting-fulfillment-user", "会议履约用户", "USER", List.of("ROLE_USER"));
    private static final AuthenticatedPrincipal STAFF = new AuthenticatedPrincipal(
            "meeting-fulfillment-staff", "会议核销员", "ADMIN", List.of("ROLE_ADMIN"));
    @Autowired MeetingManagementService management;
    @Autowired MeetingBookingService booking;
    @Autowired MeetingApprovalService approval;
    @Autowired MeetingFulfillmentService fulfillment;
    @Autowired VerificationTicketService tickets;
    @Autowired MockPaymentService payment;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 外部会议支付后可核销撤销再次核销并结束使用() {
        Fixture f = fixture("EXTERNAL");
        BookingResponse submitted = booking.submit(USER, booking.create(USER, "meeting-fulfillment-external",
                request(f, "EXTERNAL", "外部客户会议")).id());
        payment.pay(USER, submitted.orderId(), "meeting-pay-external");
        BookingResponse paid = booking.detail(USER, submitted.id());
        assertEquals("PAID", paid.status());
        VerificationTicketResponse ticket = tickets.listOwnedTickets(USER, submitted.orderId()).get(0);

        VerificationTicketResponse consumed = tickets.consume(STAFF, ticket.code(), "MEETING-DESK-01");
        assertEquals("USED", consumed.status());
        UsageRecordResponse usage = fulfillment.usageRecords(STAFF, f.date(), f.date()).stream()
                .filter(v -> v.bookingId() == submitted.id()).findFirst().orElseThrow();
        assertEquals("CHECKED_IN", usage.usageStatus());
        assertEquals("VERIFIED", usage.bookingStatus());
        assertThrows(BusinessException.class, () -> tickets.consume(STAFF, ticket.code(), "MEETING-DESK-01"));

        UsageRecordResponse revoked = fulfillment.revoke(STAFF, ticket.verificationId(),
                new RevokeRequest("扫码对象有误，撤销后重新核验"));
        assertEquals("REVOKED", revoked.usageStatus());
        assertEquals("PAID", revoked.bookingStatus());
        assertEquals("UNUSED", jdbc.queryForObject(
                "SELECT status FROM bf_verification WHERE id=?", String.class, ticket.verificationId()));

        tickets.consume(STAFF, ticket.code(), "MEETING-DESK-02");
        UsageRecordResponse completed = fulfillment.complete(STAFF, submitted.id());
        assertEquals("COMPLETED", completed.usageStatus());
        assertEquals("COMPLETED", completed.bookingStatus());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM bf_order WHERE id=?", String.class, submitted.orderId()));
        assertThrows(BusinessException.class, () -> fulfillment.revoke(STAFF,
                ticket.verificationId(), new RevokeRequest("会议结束后不可撤销")));
        assertEquals(4, fulfillment.verificationLogs(STAFF, f.date(), f.date()).stream()
                .filter(v -> v.bookingId() == submitted.id()).count());
    }

    @Test
    void 内部会议终审通过后自动签发核销码() {
        Fixture f = fixture("INTERNAL");
        long approverId = jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",
                Long.class, STAFF.databaseOpenId());
        approval.replaceRules(STAFF, new ReplaceRulesRequest(
                List.of(new RuleStepRequest(approverId, "内部会议审批"))));
        BookingResponse submitted = booking.submit(USER, booking.create(USER, "meeting-fulfillment-internal",
                request(f, "INTERNAL", "内部专题会议")).id());
        TaskResponse task = approval.applicantProgress(USER, submitted.id()).tasks().get(0);
        approval.decide(STAFF, task.taskId(), true, new DecisionRequest("同意召开"));

        List<VerificationTicketResponse> issued = tickets.listOwnedTickets(USER, submitted.orderId());
        assertEquals(1, issued.size());
        assertEquals("UNUSED", issued.get(0).status());
        assertNotNull(issued.get(0).code());
        assertEquals("APPROVED", booking.detail(USER, submitted.id()).status());
    }

    private Fixture fixture(String suffix) {
        LocalDate date = LocalDate.now();
        int day = date.getDayOfWeek().getValue();
        RoomResponse room = management.createRoom(STAFF, new RoomUpsertRequest(
                "IT_FULFILL_" + suffix, "履约会议室" + suffix, "会议中心", 60, null,
                LocalTime.of(8, 0), LocalTime.of(20, 0), "ACTIVE"));
        management.createOpening(STAFF, new OpeningRuleUpsertRequest(
                room.id(), day, LocalTime.of(9, 0), LocalTime.of(18, 0), 30, "ACTIVE"));
        management.createPrice(STAFF, new PriceRuleUpsertRequest(
                room.id(), "IT_FULFILL_PRICE_" + suffix, "履约会议价格", date, date, day,
                LocalTime.of(9, 0), LocalTime.of(18, 0), 1, 60, 60000L, 10, "ACTIVE"));
        return new Fixture(room.id(), date);
    }

    private CreateBookingRequest request(Fixture f, String type, String subject) {
        return new CreateBookingRequest(f.roomId(), type, subject, f.date(),
                LocalTime.of(9, 0), LocalTime.of(10, 0), 20,
                "会议联系人", "13800000000", null, List.of());
    }
    private record Fixture(long roomId, LocalDate date) {}
}
