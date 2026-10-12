package com.iminiapp.backend.meeting;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingApprovalModels.*;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.BookingResponse;
import com.iminiapp.backend.meeting.api.MeetingBookingModels.CreateBookingRequest;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.*;
import com.iminiapp.backend.meeting.application.*;
import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static com.iminiapp.backend.meeting.application.MeetingAvailabilityService.TIME_SLOT_OCCUPIED;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class MeetingApprovalIT {
    private static final AuthenticatedPrincipal APPLICANT = principal("approval-applicant", "内部会议申请人", "USER");
    private static final AuthenticatedPrincipal APPROVER_ONE = principal("approval-one", "一级审批人", "ADMIN");
    private static final AuthenticatedPrincipal APPROVER_TWO = principal("approval-two", "二级审批人", "ADMIN");
    private static final AuthenticatedPrincipal INTRUDER = principal("approval-intruder", "无权审批人", "ADMIN");
    private static final AuthenticatedPrincipal CONFIG_ADMIN = principal("approval-config", "审批配置管理员", "ADMIN");

    @Autowired MeetingApprovalService approval;
    @Autowired MeetingBookingService booking;
    @Autowired MeetingManagementService management;
    @Autowired MeetingAvailabilityService availability;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 两级审批按顺序激活并在终审后同步申请与订单() {
        long firstApprover = ensureUser(APPROVER_ONE);
        long secondApprover = ensureUser(APPROVER_TWO);
        ensureUser(INTRUDER);
        approval.replaceRules(CONFIG_ADMIN, new ReplaceRulesRequest(List.of(
                new RuleStepRequest(firstApprover, "部门负责人审批"),
                new RuleStepRequest(secondApprover, "分管领导审批"))));
        Fixture f = fixture("TWO_LEVEL", 2);
        BookingResponse submitted = booking.submit(APPLICANT,
                booking.create(APPLICANT, "approval-two-level", request(f, "两级审批会议", 9, 10)).id());

        ProgressResponse initial = approval.applicantProgress(APPLICANT, submitted.id());
        assertEquals("PENDING", initial.status());
        assertEquals(List.of("PENDING", "WAITING"), initial.tasks().stream().map(TaskResponse::status).toList());
        int submittedWorkflowVersion = initial.workflowVersion();
        approval.replaceRules(CONFIG_ADMIN, new ReplaceRulesRequest(
                List.of(new RuleStepRequest(secondApprover, "后续申请的新流程"))));
        assertEquals(submittedWorkflowVersion,
                approval.applicantProgress(APPLICANT, submitted.id()).workflowVersion());
        assertTrue(approval.rules(CONFIG_ADMIN).get(0).workflowVersion() > submittedWorkflowVersion);
        assertEquals(1, approval.myTasks(APPROVER_ONE, "PENDING").stream()
                .filter(t -> t.bookingId() == submitted.id()).count());
        assertThrows(BusinessException.class, () -> approval.decide(INTRUDER,
                initial.tasks().get(0).taskId(), true, new DecisionRequest("越权审批")));

        ProgressResponse afterFirst = approval.decide(APPROVER_ONE, initial.tasks().get(0).taskId(),
                true, new DecisionRequest("部门同意"));
        assertEquals(List.of("APPROVED", "PENDING"), afterFirst.tasks().stream().map(TaskResponse::status).toList());
        assertEquals("PENDING_APPROVAL", booking.detail(APPLICANT, submitted.id()).status());

        ProgressResponse completed = approval.decide(APPROVER_TWO, afterFirst.tasks().get(1).taskId(),
                true, new DecisionRequest("终审同意"));
        assertEquals("APPROVED", completed.status());
        assertNotNull(completed.completedAt());
        BookingResponse approved = booking.detail(APPLICANT, submitted.id());
        assertEquals("APPROVED", approved.status());
        assertNull(approved.releasedAt());
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM bf_order WHERE id=?", String.class, approved.orderId()));
        assertEquals(completed, approval.decide(APPROVER_TWO, afterFirst.tasks().get(1).taskId(),
                true, new DecisionRequest("重复请求")));
    }

    @Test
    void 任一节点驳回后结束流程并立即释放会议室() {
        long approverId = ensureUser(APPROVER_ONE);
        approval.replaceRules(CONFIG_ADMIN, new ReplaceRulesRequest(
                List.of(new RuleStepRequest(approverId, "部门审批"))));
        Fixture f = fixture("REJECT_RELEASE", 3);
        BookingResponse firstDraft = booking.create(APPLICANT, "approval-reject-first",
                request(f, "待驳回会议", 9, 10));
        BookingResponse competingDraft = booking.create(APPLICANT, "approval-reject-second",
                request(f, "候补会议", 9, 10));
        BookingResponse submitted = booking.submit(APPLICANT, firstDraft.id());
        assertEquals(TIME_SLOT_OCCUPIED, availability.availability(
                f.roomId(), f.date(), 20, LocalTime.of(9, 0), LocalTime.of(10, 0)).unavailableCode());

        TaskResponse task = approval.applicantProgress(APPLICANT, submitted.id()).tasks().get(0);
        ProgressResponse rejected = approval.decide(APPROVER_ONE, task.taskId(), false,
                new DecisionRequest("会议目的和材料不完整"));
        assertEquals("REJECTED", rejected.status());
        BookingResponse rejectedBooking = booking.detail(APPLICANT, submitted.id());
        assertEquals("REJECTED", rejectedBooking.status());
        assertNotNull(rejectedBooking.releasedAt());

        BookingResponse competing = booking.submit(APPLICANT, competingDraft.id());
        assertEquals("PENDING_APPROVAL", competing.status());
    }

    @Test
    void 申请人可撤回待审批流程且非申请人不能查看() {
        long firstApprover = ensureUser(APPROVER_ONE);
        long secondApprover = ensureUser(APPROVER_TWO);
        approval.replaceRules(CONFIG_ADMIN, new ReplaceRulesRequest(List.of(
                new RuleStepRequest(firstApprover, "一级审批"),
                new RuleStepRequest(secondApprover, "二级审批"))));
        Fixture f = fixture("WITHDRAW", 4);
        BookingResponse submitted = booking.submit(APPLICANT,
                booking.create(APPLICANT, "approval-withdraw", request(f, "撤回会议", 10, 11)).id());

        approval.withdraw(APPLICANT, submitted.id());
        ProgressResponse progress = approval.applicantProgress(APPLICANT, submitted.id());
        assertEquals("WITHDRAWN", progress.status());
        assertTrue(progress.tasks().stream().allMatch(t -> "CANCELLED".equals(t.status())));
        BookingResponse cancelled = booking.detail(APPLICANT, submitted.id());
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.releasedAt());

        AuthenticatedPrincipal other = principal("approval-other", "其他申请人", "USER");
        ensureUser(other);
        assertThrows(BusinessException.class, () -> approval.applicantProgress(other, submitted.id()));
    }

    private Fixture fixture(String suffix, int day) {
        LocalDate date = nextDate(day);
        RoomResponse room = management.createRoom(CONFIG_ADMIN, new RoomUpsertRequest(
                "IT_APPROVAL_" + suffix, "审批会议室" + suffix, "会议中心", 80, null,
                LocalTime.of(8, 0), LocalTime.of(20, 0), "ACTIVE"));
        management.createOpening(CONFIG_ADMIN, new OpeningRuleUpsertRequest(
                room.id(), day, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, "ACTIVE"));
        management.createPrice(CONFIG_ADMIN, new PriceRuleUpsertRequest(
                room.id(), "IT_APPROVAL_PRICE_" + suffix, "内部会议价格", date.minusDays(1), date.plusDays(1),
                day, LocalTime.of(9, 0), LocalTime.of(12, 0), 1, 80, 50000L, 10, "ACTIVE"));
        return new Fixture(room.id(), date);
    }

    private CreateBookingRequest request(Fixture f, String subject, int startHour, int endHour) {
        return new CreateBookingRequest(f.roomId(), "INTERNAL", subject, f.date(),
                LocalTime.of(startHour, 0), LocalTime.of(endHour, 0), 20,
                "内部员工", "13800000000", null, List.of());
    }

    private long ensureUser(AuthenticatedPrincipal principal) {
        jdbc.update("""
                INSERT INTO bf_user(wechat_openid,nickname,real_name,status,last_login_at)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE nickname=VALUES(nickname),real_name=VALUES(real_name),status='ACTIVE'
                """, principal.databaseOpenId(), principal.displayName(), principal.displayName());
        return jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",
                Long.class, principal.databaseOpenId());
    }

    private LocalDate nextDate(int dayOfWeek) {
        LocalDate candidate = LocalDate.now().with(
                TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(dayOfWeek)));
        return candidate.isAfter(LocalDate.now()) ? candidate : candidate.plusWeeks(1);
    }

    private static AuthenticatedPrincipal principal(String id, String name, String accountType) {
        return new AuthenticatedPrincipal(id, name, accountType, List.of("ROLE_" + accountType));
    }

    private record Fixture(long roomId, LocalDate date) {}
}
