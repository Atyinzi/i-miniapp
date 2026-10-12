package com.iminiapp.backend.meeting.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingApprovalModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.iminiapp.backend.verification.application.VerificationTicketService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MeetingApprovalService {
    private static final String BUSINESS_TYPE = "MEETING_RESERVATION";
    private static final Set<String> TASK_STATUSES = Set.of("PENDING", "WAITING", "APPROVED", "REJECTED", "CANCELLED");
    private final JdbcTemplate jdbc;
    private final VerificationTicketService verificationTicketService;

    public MeetingApprovalService(JdbcTemplate jdbc, VerificationTicketService verificationTicketService) {
        this.jdbc = jdbc;
        this.verificationTicketService = verificationTicketService;
    }

    @Transactional
    public List<RuleResponse> replaceRules(AuthenticatedPrincipal principal, ReplaceRulesRequest request) {
        requireAdmin(principal);
        long actor = ensureUser(principal);
        if (request == null || request.steps() == null || request.steps().isEmpty() || request.steps().size() > 20)
            throw invalid("会议审批流程必须配置1—20个步骤");
        Set<Long> approvers = new HashSet<>();
        for (RuleStepRequest step : request.steps()) {
            if (step == null || step.approverUserId() < 1 || !StringUtils.hasText(step.stepName())
                    || step.stepName().trim().length() > 100)
                throw invalid("审批步骤名称或审批人不合法");
            if (!approvers.add(step.approverUserId())) throw invalid("同一审批人不能在流程中重复出现");
            requireActiveUser(step.approverUserId());
        }
        int current = jdbc.queryForObject(
                "SELECT current_version FROM bf_meeting_approval_config WHERE id=1 FOR UPDATE", Integer.class);
        int version = Math.addExact(current, 1);
        jdbc.update("UPDATE bf_meeting_approval_rule SET status='INACTIVE' WHERE status='ACTIVE'");
        for (int i = 0; i < request.steps().size(); i++) {
            RuleStepRequest step = request.steps().get(i);
            jdbc.update("""
                    INSERT INTO bf_meeting_approval_rule
                    (workflow_version,step_no,step_name,approver_id,status,created_by)
                    VALUES (?,?,?,?,'ACTIVE',?)
                    """, version, i + 1, step.stepName().trim(), step.approverUserId(), actor);
        }
        jdbc.update("UPDATE bf_meeting_approval_config SET current_version=?,updated_by=? WHERE id=1", version, actor);
        audit(actor, "MEETING_APPROVAL_RULE_REPLACE", "MEETING_APPROVAL_CONFIG", 1,
                "审批流程升级至版本" + version);
        return rulesForVersion(version);
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> rules(AuthenticatedPrincipal principal) {
        requireAdmin(principal);
        Integer version = jdbc.queryForObject(
                "SELECT current_version FROM bf_meeting_approval_config WHERE id=1", Integer.class);
        return version == null || version == 0 ? List.of() : rulesForVersion(version);
    }

    @Transactional
    public long launch(long bookingId, long applicantId, String title) {
        List<Long> existing = jdbc.queryForList("""
                SELECT id FROM bf_approval_instance WHERE business_type=? AND business_id=?
                """, Long.class, BUSINESS_TYPE, bookingId);
        if (!existing.isEmpty()) return existing.get(0);
        int version = jdbc.queryForObject(
                "SELECT current_version FROM bf_meeting_approval_config WHERE id=1 FOR UPDATE", Integer.class);
        List<Rule> rules = ruleValues(version);
        if (version < 1 || rules.isEmpty()) throw conflict("内部会议审批流程尚未配置");

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bf_approval_instance
                    (business_type,business_id,applicant_id,title,status,current_step,workflow_version)
                    VALUES (?,?,?,?,'PENDING',1,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, BUSINESS_TYPE); ps.setLong(2, bookingId); ps.setLong(3, applicantId);
            ps.setString(4, truncate(title, 200)); ps.setInt(5, version);
            return ps;
        }, keys);
        long instanceId = Objects.requireNonNull(keys.getKey()).longValue();
        for (Rule rule : rules) {
            jdbc.update("""
                    INSERT INTO bf_approval_task(instance_id,step_no,step_name,approver_id,status)
                    VALUES (?,?,?,?,?)
                    """, instanceId, rule.step(), rule.name(), rule.approverId(),
                    rule.step() == 1 ? "PENDING" : "WAITING");
        }
        return instanceId;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> myTasks(AuthenticatedPrincipal principal, String status) {
        requireAdmin(principal);
        long approverId = requireUser(principal);
        String normalized = normalizeTaskStatus(status);
        return jdbc.query("""
                SELECT t.id task_id,t.instance_id,i.business_id,r.booking_no,r.meeting_subject,
                       t.step_no,t.step_name,t.approver_id,COALESCE(u.real_name,u.nickname) approver_name,
                       t.status,t.comment,t.handled_at,t.created_at
                FROM bf_approval_task t
                JOIN bf_approval_instance i ON i.id=t.instance_id AND i.business_type=?
                JOIN bf_meeting_reservation r ON r.id=i.business_id
                JOIN bf_user u ON u.id=t.approver_id
                WHERE t.approver_id=? AND t.status=? ORDER BY t.created_at,t.id
                """, taskMapper(), BUSINESS_TYPE, approverId, normalized);
    }

    @Transactional(readOnly = true)
    public ProgressResponse progress(AuthenticatedPrincipal principal, long bookingId) {
        requireAdmin(principal);
        return progressInternal(bookingId);
    }

    @Transactional(readOnly = true)
    public ProgressResponse applicantProgress(AuthenticatedPrincipal principal, long bookingId) {
        long userId = requireUser(principal);
        Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bf_meeting_reservation WHERE id=? AND user_id=? AND booking_type='INTERNAL'",
                Integer.class, bookingId, userId);
        if (owned == null || owned == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "内部会议申请不存在或无权访问");
        return progressInternal(bookingId);
    }

    @Transactional
    public ProgressResponse decide(AuthenticatedPrincipal principal, long taskId, boolean approved,
                                   DecisionRequest request) {
        requireAdmin(principal);
        long operatorId = requireUser(principal);
        PreliminaryTask preliminary = preliminaryTask(taskId);
        if (preliminary.approverId() != operatorId) throw forbidden("当前用户不是该审批节点的审批人");
        ReservationDay day = reservationDay(preliminary.bookingId());
        lockRoomDay(day.roomId(), day.date());
        LockedReservation reservation = lockReservation(preliminary.bookingId());
        LockedTask task = lockTask(taskId);
        ApprovalInstance instance = lockInstance(task.instanceId());
        String target = approved ? "APPROVED" : "REJECTED";
        if (target.equals(task.status())) return progressInternal(reservation.id());
        if (!"PENDING".equals(task.status()) || !"PENDING".equals(instance.status())
                || instance.currentStep() != task.stepNo())
            throw conflict("审批任务已处理或尚未轮到当前节点");
        if (!"PENDING_APPROVAL".equals(reservation.status())) throw conflict("会议申请不处于待审批状态");
        String comment = normalizeComment(request == null ? null : request.comment());
        if (!approved && comment == null) throw invalid("驳回时必须填写原因");

        jdbc.update("""
                UPDATE bf_approval_task SET status=?,comment=?,handled_at=CURRENT_TIMESTAMP WHERE id=?
                """, target, comment, taskId);
        if (!approved) {
            reject(instance, reservation, operatorId, task, comment);
        } else {
            Integer nextStep = jdbc.queryForObject("""
                    SELECT MIN(step_no) FROM bf_approval_task WHERE instance_id=? AND step_no>?
                    """, Integer.class, instance.id(), task.stepNo());
            if (nextStep == null) approveFinal(instance, reservation, operatorId, task, comment);
            else {
                jdbc.update("UPDATE bf_approval_task SET status='PENDING' WHERE instance_id=? AND step_no=? AND status='WAITING'",
                        instance.id(), nextStep);
                jdbc.update("UPDATE bf_approval_instance SET current_step=? WHERE id=?", nextStep, instance.id());
                audit(operatorId, "MEETING_APPROVAL_STEP_APPROVED", "MEETING_RESERVATION", reservation.id(),
                        "第" + task.stepNo() + "级审批通过");
            }
        }
        return progressInternal(reservation.id());
    }

    @Transactional
    public void withdraw(AuthenticatedPrincipal principal, long bookingId) {
        long userId = requireUser(principal);
        ReservationDay day = ownedReservationDay(userId, bookingId);
        lockRoomDay(day.roomId(), day.date());
        LockedReservation reservation = lockReservation(bookingId);
        if (reservation.userId() != userId || !"INTERNAL".equals(reservation.type()))
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "内部会议申请不存在或无权访问");
        if (!"PENDING_APPROVAL".equals(reservation.status())) throw conflict("只有待审批会议申请可以撤回");
        ApprovalInstance instance = lockInstanceByBooking(bookingId);
        if (!"PENDING".equals(instance.status())) throw conflict("审批流程已经结束，不能撤回");

        jdbc.update("UPDATE bf_approval_task SET status='CANCELLED',handled_at=CURRENT_TIMESTAMP WHERE instance_id=? AND status IN ('PENDING','WAITING')",
                instance.id());
        jdbc.update("UPDATE bf_approval_instance SET status='WITHDRAWN',completed_at=CURRENT_TIMESTAMP WHERE id=?", instance.id());
        jdbc.update("""
                UPDATE bf_meeting_reservation SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,released_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, bookingId);
        jdbc.update("UPDATE bf_order SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=?", reservation.orderId());
        statusLogs(reservation, userId, "CANCELLED", "申请人撤回内部会议申请", "WITHDRAW");
        audit(userId, "MEETING_APPROVAL_WITHDRAW", "MEETING_RESERVATION", bookingId, "申请人撤回审批");
    }

    private void reject(ApprovalInstance instance, LockedReservation reservation, long operatorId,
                        LockedTask task, String comment) {
        jdbc.update("UPDATE bf_approval_task SET status='CANCELLED',handled_at=CURRENT_TIMESTAMP WHERE instance_id=? AND step_no>? AND status='WAITING'",
                instance.id(), task.stepNo());
        jdbc.update("UPDATE bf_approval_instance SET status='REJECTED',completed_at=CURRENT_TIMESTAMP WHERE id=?", instance.id());
        jdbc.update("""
                UPDATE bf_meeting_reservation SET status='REJECTED',released_at=CURRENT_TIMESTAMP WHERE id=?
                """, reservation.id());
        jdbc.update("UPDATE bf_order SET status='REJECTED' WHERE id=?", reservation.orderId());
        statusLogs(reservation, operatorId, "REJECTED", comment, "REJECT:" + task.id());
        audit(operatorId, "MEETING_APPROVAL_REJECTED", "MEETING_RESERVATION", reservation.id(), comment);
    }

    private void approveFinal(ApprovalInstance instance, LockedReservation reservation, long operatorId,
                              LockedTask task, String comment) {
        jdbc.update("UPDATE bf_approval_instance SET status='APPROVED',completed_at=CURRENT_TIMESTAMP WHERE id=?", instance.id());
        jdbc.update("UPDATE bf_meeting_reservation SET status='APPROVED' WHERE id=?", reservation.id());
        jdbc.update("UPDATE bf_order SET status='APPROVED' WHERE id=?", reservation.orderId());
        verificationTicketService.issueOrderTickets(reservation.orderId());
        statusLogs(reservation, operatorId, "APPROVED", comment, "APPROVE:" + task.id());
        audit(operatorId, "MEETING_APPROVAL_APPROVED", "MEETING_RESERVATION", reservation.id(), comment);
    }

    private void statusLogs(LockedReservation reservation, long operatorId, String target,
                            String reason, String suffix) {
        jdbc.update("""
                INSERT INTO bf_meeting_reservation_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'PENDING_APPROVAL',?,?,?,?)
                """, reservation.id(), target, operatorId, reason,
                "MEETING-APPROVAL-" + suffix + ":" + reservation.id());
        jdbc.update("""
                INSERT INTO bf_order_status_log
                (order_id,from_status,to_status,operator_id,reason,idempotency_key)
                VALUES (?,'PENDING_APPROVAL',?,?,?,?)
                """, reservation.orderId(), target, operatorId, reason,
                "MEETING-ORDER-APPROVAL-" + suffix + ":" + reservation.id());
    }

    private ProgressResponse progressInternal(long bookingId) {
        List<ProgressHead> rows = jdbc.query("""
                SELECT i.id,i.business_id,r.booking_no,i.title,i.status,i.current_step,i.workflow_version,
                       i.applicant_id,i.submitted_at,i.completed_at
                FROM bf_approval_instance i JOIN bf_meeting_reservation r ON r.id=i.business_id
                WHERE i.business_type=? AND i.business_id=?
                """, (rs, n) -> new ProgressHead(rs.getLong("id"), rs.getLong("business_id"),
                rs.getString("booking_no"), rs.getString("title"), rs.getString("status"),
                rs.getInt("current_step"), rs.getInt("workflow_version"), rs.getLong("applicant_id"),
                rs.getObject("submitted_at", LocalDateTime.class), rs.getObject("completed_at", LocalDateTime.class)),
                BUSINESS_TYPE, bookingId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议审批流程不存在");
        ProgressHead h = rows.get(0);
        return new ProgressResponse(h.instanceId(), h.bookingId(), h.bookingNo(), h.title(), h.status(),
                h.currentStep(), h.workflowVersion(), h.applicantId(), h.submittedAt(), h.completedAt(),
                tasks(h.instanceId()));
    }

    private List<TaskResponse> tasks(long instanceId) {
        return jdbc.query("""
                SELECT t.id task_id,t.instance_id,i.business_id,r.booking_no,r.meeting_subject,
                       t.step_no,t.step_name,t.approver_id,COALESCE(u.real_name,u.nickname) approver_name,
                       t.status,t.comment,t.handled_at,t.created_at
                FROM bf_approval_task t JOIN bf_approval_instance i ON i.id=t.instance_id
                JOIN bf_meeting_reservation r ON r.id=i.business_id JOIN bf_user u ON u.id=t.approver_id
                WHERE t.instance_id=? ORDER BY t.step_no,t.id
                """, taskMapper(), instanceId);
    }

    private org.springframework.jdbc.core.RowMapper<TaskResponse> taskMapper() {
        return (rs, n) -> new TaskResponse(rs.getLong("task_id"), rs.getLong("instance_id"),
                rs.getLong("business_id"), rs.getString("booking_no"), rs.getString("meeting_subject"),
                rs.getInt("step_no"), rs.getString("step_name"), rs.getLong("approver_id"),
                rs.getString("approver_name"), rs.getString("status"), rs.getString("comment"),
                rs.getObject("handled_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class));
    }

    private List<RuleResponse> rulesForVersion(int version) {
        return jdbc.query("""
                SELECT r.id,r.workflow_version,r.step_no,r.step_name,r.approver_id,
                       COALESCE(u.real_name,u.nickname) approver_name,r.status
                FROM bf_meeting_approval_rule r JOIN bf_user u ON u.id=r.approver_id
                WHERE r.workflow_version=? ORDER BY r.step_no
                """, (rs, n) -> new RuleResponse(rs.getLong("id"), rs.getInt("workflow_version"),
                rs.getInt("step_no"), rs.getString("step_name"), rs.getLong("approver_id"),
                rs.getString("approver_name"), rs.getString("status")), version);
    }

    private List<Rule> ruleValues(int version) {
        if (version < 1) return List.of();
        return jdbc.query("""
                SELECT step_no,step_name,approver_id FROM bf_meeting_approval_rule
                WHERE workflow_version=? AND status='ACTIVE' ORDER BY step_no
                """, (rs, n) -> new Rule(rs.getInt("step_no"), rs.getString("step_name"),
                rs.getLong("approver_id")), version);
    }

    private PreliminaryTask preliminaryTask(long taskId) {
        List<PreliminaryTask> rows = jdbc.query("""
                SELECT t.approver_id,i.business_id FROM bf_approval_task t JOIN bf_approval_instance i ON i.id=t.instance_id
                WHERE t.id=? AND i.business_type=?
                """, (rs, n) -> new PreliminaryTask(rs.getLong("approver_id"), rs.getLong("business_id")),
                taskId, BUSINESS_TYPE);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议审批任务不存在");
        return rows.get(0);
    }

    private LockedTask lockTask(long taskId) {
        List<LockedTask> rows = jdbc.query("""
                SELECT id,instance_id,step_no,approver_id,status FROM bf_approval_task WHERE id=? FOR UPDATE
                """, (rs, n) -> new LockedTask(rs.getLong("id"), rs.getLong("instance_id"),
                rs.getInt("step_no"), rs.getLong("approver_id"), rs.getString("status")), taskId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议审批任务不存在");
        return rows.get(0);
    }

    private ApprovalInstance lockInstance(long instanceId) {
        return jdbc.queryForObject("""
                SELECT id,business_id,status,current_step FROM bf_approval_instance WHERE id=? FOR UPDATE
                """, (rs, n) -> new ApprovalInstance(rs.getLong("id"), rs.getLong("business_id"),
                rs.getString("status"), rs.getInt("current_step")), instanceId);
    }

    private ApprovalInstance lockInstanceByBooking(long bookingId) {
        List<ApprovalInstance> rows = jdbc.query("""
                SELECT id,business_id,status,current_step FROM bf_approval_instance
                WHERE business_type=? AND business_id=? FOR UPDATE
                """, (rs, n) -> new ApprovalInstance(rs.getLong("id"), rs.getLong("business_id"),
                rs.getString("status"), rs.getInt("current_step")), BUSINESS_TYPE, bookingId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "会议审批流程不存在");
        return rows.get(0);
    }

    private LockedReservation lockReservation(long bookingId) {
        return jdbc.queryForObject("""
                SELECT id,user_id,order_id,booking_type,status FROM bf_meeting_reservation WHERE id=? FOR UPDATE
                """, (rs, n) -> new LockedReservation(rs.getLong("id"), rs.getLong("user_id"),
                rs.getLong("order_id"), rs.getString("booking_type"), rs.getString("status")), bookingId);
    }

    private ReservationDay reservationDay(long bookingId) {
        return jdbc.queryForObject("SELECT room_id,booking_date FROM bf_meeting_reservation WHERE id=?",
                (rs, n) -> new ReservationDay(rs.getLong("room_id"), rs.getObject("booking_date", LocalDate.class)), bookingId);
    }

    private ReservationDay ownedReservationDay(long userId, long bookingId) {
        List<ReservationDay> rows = jdbc.query("""
                SELECT room_id,booking_date FROM bf_meeting_reservation WHERE id=? AND user_id=?
                """, (rs, n) -> new ReservationDay(rs.getLong("room_id"),
                rs.getObject("booking_date", LocalDate.class)), bookingId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "内部会议申请不存在或无权访问");
        return rows.get(0);
    }

    private void lockRoomDay(long roomId, LocalDate date) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bf_meeting_room_day_lock WHERE room_id=? AND booking_date=?",
                Integer.class, roomId, date);
        if (exists == null || exists == 0) {
            jdbc.queryForObject("SELECT id FROM bf_meeting_room WHERE id=? FOR UPDATE", Long.class, roomId);
            jdbc.update("INSERT IGNORE INTO bf_meeting_room_day_lock(room_id,booking_date) VALUES (?,?)", roomId, date);
        }
        jdbc.queryForObject("SELECT room_id FROM bf_meeting_room_day_lock WHERE room_id=? AND booking_date=? FOR UPDATE",
                Long.class, roomId, date);
    }

    private void requireActiveUser(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bf_user WHERE id=? AND status='ACTIVE'", Integer.class, id);
        if (count == null || count == 0) throw invalid("审批人不存在或已停用：" + id);
    }

    private long ensureUser(AuthenticatedPrincipal p) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, p.databaseOpenId());
        if (!ids.isEmpty()) return ids.get(0);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO bf_user(wechat_openid,nickname,real_name,status,last_login_at)
                    VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, p.databaseOpenId()); ps.setString(2, p.displayName()); ps.setString(3, p.displayName());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private long requireUser(AuthenticatedPrincipal p) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'",
                Long.class, p.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前账号未关联有效平台用户");
        return ids.get(0);
    }

    private void requireAdmin(AuthenticatedPrincipal p) {
        if (p == null || !"ADMIN".equals(p.accountType())) throw forbidden("仅管理端账号可以执行会议审批操作");
    }

    private String normalizeTaskStatus(String status) {
        String value = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "PENDING";
        if (!TASK_STATUSES.contains(value)) throw invalid("审批任务状态不正确");
        return value;
    }

    private String normalizeComment(String comment) {
        if (!StringUtils.hasText(comment)) return null;
        String value = comment.trim();
        if (value.length() > 500) throw invalid("审批意见最长500字符");
        return value;
    }

    private String truncate(String value, int length) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "内部会议审批";
        return normalized.length() > length ? normalized.substring(0, length) : normalized;
    }

    private void audit(Long actor, String action, String target, long id, String detail) {
        jdbc.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?,?,?,JSON_OBJECT('detail',?))
                """, actor, action, target, String.valueOf(id), detail == null ? "" : detail);
    }

    private BusinessException invalid(String message) { return new BusinessException(CommonErrorCode.INVALID_REQUEST, message); }
    private BusinessException conflict(String message) { return new BusinessException(CommonErrorCode.CONFLICT, message); }
    private BusinessException forbidden(String message) { return new BusinessException(CommonErrorCode.FORBIDDEN, message); }

    private record Rule(int step, String name, long approverId) {}
    private record PreliminaryTask(long approverId, long bookingId) {}
    private record LockedTask(long id, long instanceId, int stepNo, long approverId, String status) {}
    private record ApprovalInstance(long id, long bookingId, String status, int currentStep) {}
    private record LockedReservation(long id, long userId, long orderId, String type, String status) {}
    private record ReservationDay(long roomId, LocalDate date) {}
    private record ProgressHead(long instanceId, long bookingId, String bookingNo, String title, String status,
                                int currentStep, int workflowVersion, long applicantId,
                                LocalDateTime submittedAt, LocalDateTime completedAt) {}
}
