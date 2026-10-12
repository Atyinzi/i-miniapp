package com.iminiapp.backend.verification.application;

import com.iminiapp.backend.dining.application.DiningFulfillmentPolicy;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.hotel.api.HotelOperationModels.CheckInCredentialResponse;
import com.iminiapp.backend.hotel.application.HotelCheckinCredentialService;
import com.iminiapp.backend.order.domain.OrderStatePolicy;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.verification.api.VerificationTicketResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VerificationTicketService {
    private static final Pattern TIME_SLOT_PATTERN = Pattern.compile(
            "^\\s*([01]\\d|2[0-3]):([0-5]\\d)\\s*-\\s*([01]\\d|2[0-3]):([0-5]\\d)\\s*$");
    private final JdbcTemplate jdbcTemplate;
    private final byte[] signingKey;
    private final QrCodeService qrCodeService;
    private final HotelCheckinCredentialService hotelCredentialService;

    public VerificationTicketService(JdbcTemplate jdbcTemplate,
                                     @Value("${app.verification.signing-key}") String signingKey,
                                     QrCodeService qrCodeService,
                                     HotelCheckinCredentialService hotelCredentialService) {
        this.jdbcTemplate = jdbcTemplate;
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.qrCodeService = qrCodeService;
        this.hotelCredentialService = hotelCredentialService;
    }

    @Transactional
    public void issueOrderTickets(long orderId) {
        List<IssueItem> items = jdbcTemplate.query("""
                SELECT i.id, i.quantity, o.order_no
                FROM bf_order_item i
                JOIN bf_order o ON o.id = i.order_id
                JOIN bf_resource_sku sku ON sku.id=i.sku_id
                WHERE o.id = ? AND o.status IN ('PAID', 'READY', 'APPROVED', 'COMPLETED')
                  AND i.resource_type IN ('SCENIC_TICKET', 'SPORT_VENUE', 'MEETING_BOOKING')
                  AND NOT EXISTS (SELECT 1 FROM bf_resource_package package WHERE package.package_code=sku.sku_code)
                """, (rs, n) -> new IssueItem(rs.getLong("id"), rs.getInt("quantity"), rs.getString("order_no")), orderId);
        for (IssueItem item : items) {
            List<Long> visitorIds = jdbcTemplate.queryForList("""
                    SELECT id FROM bf_order_person WHERE order_item_id=? AND person_type='VISITOR' ORDER BY id
                    """, Long.class, item.orderItemId());
            for (int ticketNo = 1; ticketNo <= item.quantity(); ticketNo++) {
                String code = ticketCode(item.orderNo(), item.orderItemId(), null, ticketNo);
                Long visitorId = ticketNo <= visitorIds.size() ? visitorIds.get(ticketNo - 1) : null;
                jdbcTemplate.update("""
                        INSERT IGNORE INTO bf_verification
                        (order_item_id, order_person_id, ticket_no, verification_code_hash, status)
                        VALUES (?, ?, ?, ?, 'UNUSED')
                        """, item.orderItemId(), visitorId, ticketNo, sha256(code));
                if (visitorId != null) {
                    jdbcTemplate.update("""
                            UPDATE bf_verification SET order_person_id=?
                            WHERE order_item_id=? AND ticket_no=? AND order_person_id IS NULL
                            """, visitorId, item.orderItemId(), ticketNo);
                }
            }
        }
        List<PackageIssueItem> packageItems = jdbcTemplate.query("""
                SELECT package_item.id,package_item.order_item_id,package_item.quantity,o.order_no
                FROM bf_order_package_item package_item
                JOIN bf_order_item item ON item.id=package_item.order_item_id
                JOIN bf_order o ON o.id=item.order_id
                WHERE o.id=? AND o.status IN ('PAID','READY','COMPLETED')
                ORDER BY package_item.id
                """, (rs, n) -> new PackageIssueItem(rs.getLong("id"), rs.getLong("order_item_id"),
                rs.getInt("quantity"), rs.getString("order_no")), orderId);
        for (PackageIssueItem item : packageItems) {
            List<Long> visitorIds = jdbcTemplate.queryForList("""
                    SELECT id FROM bf_order_person WHERE order_item_id=? AND person_type='VISITOR' ORDER BY id
                    """, Long.class, item.orderItemId());
            for (int ticketNo = 1; ticketNo <= item.quantity(); ticketNo++) {
                String code = ticketCode(item.orderNo(), item.orderItemId(), item.packageItemId(), ticketNo);
                Long visitorId = visitorIds.isEmpty() ? null : visitorIds.get((ticketNo - 1) % visitorIds.size());
                jdbcTemplate.update("""
                        INSERT IGNORE INTO bf_verification
                        (order_item_id,order_person_id,order_package_item_id,ticket_no,verification_code_hash,status)
                        VALUES (?,?,?,?,?,'UNUSED')
                        """, item.orderItemId(), visitorId, item.packageItemId(), ticketNo, sha256(code));
                if (visitorId != null) {
                    jdbcTemplate.update("""
                            UPDATE bf_verification SET order_person_id=?
                            WHERE order_package_item_id=? AND ticket_no=? AND order_person_id IS NULL
                            """, visitorId, item.packageItemId(), ticketNo);
                }
            }
        }
        jdbcTemplate.update("""
                UPDATE bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id
                JOIN bf_sport_booking b ON b.order_item_id=i.id
                SET v.valid_from=TIMESTAMP(b.booking_date,b.start_time),
                    v.valid_until=TIMESTAMP(b.booking_date,b.end_time)
                WHERE i.order_id=? AND v.valid_from IS NULL
                """, orderId);
        jdbcTemplate.update("""
                UPDATE bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id
                JOIN bf_meeting_reservation r ON r.order_id=i.order_id
                SET v.valid_from=TIMESTAMP(r.booking_date,r.start_time),
                    v.valid_until=TIMESTAMP(r.booking_date,r.end_time)
                WHERE i.order_id=? AND v.valid_from IS NULL
                """, orderId);
    }

    @Transactional
    public List<VerificationTicketResponse> listOwnedTickets(AuthenticatedPrincipal principal, long orderId) {
        long userId = requireDatabaseUser(principal);
        Integer owned = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bf_order WHERE id = ? AND user_id = ?", Integer.class, orderId, userId);
        if (owned == null || owned == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "订单不存在或无权访问");
        }
        issueOrderTickets(orderId);
        List<VerificationTicketResponse> tickets = jdbcTemplate.query("""
                SELECT v.id, v.ticket_no, v.status, v.verified_at,v.order_package_item_id,
                       i.id order_item_id, COALESCE(package_item.component_sku_name,i.resource_name) resource_name,
                       COALESCE(package_item.component_resource_type,i.resource_type) resource_type, i.service_date,
                       o.id order_id, o.order_no
                FROM bf_verification v
                JOIN bf_order_item i ON i.id = v.order_item_id
                JOIN bf_order o ON o.id = i.order_id
                LEFT JOIN bf_order_package_item package_item ON package_item.id=v.order_package_item_id
                WHERE o.id = ? AND o.user_id = ?
                ORDER BY i.id, v.ticket_no
                """, (rs, n) -> new VerificationTicketResponse(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no"),
                rs.getLong("order_item_id"), rs.getInt("ticket_no"), rs.getString("resource_name"),
                rs.getString("resource_type"), ticketCode(rs.getString("order_no"), rs.getLong("order_item_id"),
                rs.getObject("order_package_item_id", Long.class), rs.getInt("ticket_no")),
                qrCodeService.asDataUrl(ticketCode(rs.getString("order_no"), rs.getLong("order_item_id"),
                        rs.getObject("order_package_item_id", Long.class), rs.getInt("ticket_no"))), rs.getString("status"),
                rs.getObject("service_date", LocalDate.class), rs.getObject("verified_at", LocalDateTime.class)),
                orderId, userId);
        if (!tickets.isEmpty()) return tickets;
        List<VerificationTicketResponse> dining = diningCredential(orderId, userId);
        if (!dining.isEmpty()) return dining;
        return hotelCredential(principal, orderId, userId);
    }

    @Transactional
    public VerificationTicketResponse consume(AuthenticatedPrincipal operator, String code) {
        return consume(operator, code, null);
    }

    @Transactional
    public VerificationTicketResponse consume(AuthenticatedPrincipal operator, String code, String deviceNo) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "核销码不能为空");
        }
        if (StringUtils.hasText(deviceNo) && deviceNo.trim().length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "核销设备编号最长64字符");
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        long operatorId = ensureDatabaseOperator(operator);
        List<LockedTicket> rows = jdbcTemplate.query(ticketLockSelect() + " WHERE v.verification_code_hash=? FOR UPDATE",
                lockedTicketMapper(), sha256(normalizedCode));
        if (rows.isEmpty()) return consumeDiningCredential(operatorId, normalizedCode, deviceNo);
        return consumeLockedTicket(operator, operatorId, rows.get(0), "QR_CODE",
                StringUtils.hasText(deviceNo) ? deviceNo.trim() : null);
    }

    private VerificationTicketResponse consumeDiningCredential(long operatorId,String code,String deviceNo) {
        List<DiningCredential> rows=jdbcTemplate.query("""
                SELECT r.id,r.order_id,o.order_no,i.id order_item_id,r.reservation_date,r.status,
                       r.fulfillment_type,s.name store_name
                FROM bf_dining_reservation r JOIN bf_order o ON o.id=r.order_id
                JOIN bf_order_item i ON i.order_id=o.id JOIN bf_dining_store s ON s.id=r.store_id
                WHERE r.verification_code_hash=? FOR UPDATE
                """,(rs,n)->new DiningCredential(rs.getLong("id"),rs.getLong("order_id"),rs.getString("order_no"),
                rs.getLong("order_item_id"),rs.getObject("reservation_date",LocalDate.class),rs.getString("status"),
                rs.getString("fulfillment_type"),rs.getString("store_name")),sha256(code));
        if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"核销码不存在");
        DiningCredential value=rows.get(0);
        if(!DiningFulfillmentPolicy.canVerifyOnSite(value.fulfillmentType()))
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,"仅堂食订单支持到店二维码核销");
        if(!value.reservationDate().equals(LocalDate.now()))
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,"餐饮凭证仅可在预订日期使用");
        if(!List.of("PAID","PREPARING","READY").contains(value.status()))
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,"餐饮凭证当前不可核销，状态为 "+value.status());
        jdbcTemplate.update("""
                UPDATE bf_dining_reservation SET status='VERIFIED',verified_by=?,verified_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('PAID','PREPARING','READY')
                """,operatorId,value.id());
        jdbcTemplate.update("""
                INSERT INTO bf_dining_status_log
                (reservation_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,?,'VERIFIED',?,? ,?)
                """,value.id(),value.status(),operatorId,
                "工作人员二维码核销，设备："+(StringUtils.hasText(deviceNo)?deviceNo.trim():"-"),
                "DINING-VERIFY:"+value.id());
        LocalDateTime verifiedAt=jdbcTemplate.queryForObject(
                "SELECT verified_at FROM bf_dining_reservation WHERE id=?",LocalDateTime.class,value.id());
        return new VerificationTicketResponse(value.id(),value.orderId(),value.orderNo(),value.orderItemId(),1,
                value.storeName()+"到店凭证","DINING_ORDER",null,null,"USED",value.reservationDate(),verifiedAt);
    }

    private VerificationTicketResponse consumeLockedTicket(AuthenticatedPrincipal operator, long operatorId,
                                                            LockedTicket ticket, String verificationChannel,
                                                            String deviceNo) {
        if (!"UNUSED".equals(ticket.status())) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "该电子票已核销或已失效，状态为 " + ticket.status());
        }
        if (!OrderStatePolicy.canVerify(ticket.orderStatus())) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "订单状态不允许核销：" + ticket.orderStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        if (ticket.serviceDate() != null && !ticket.serviceDate().equals(now.toLocalDate())) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "电子票仅可在服务日期 " + ticket.serviceDate() + " 使用");
        }
        validateExperienceTimeSlot(ticket.productKind(), ticket.serviceDate(), ticket.timeSlot(), now);
        SportState sport = lockSportState(ticket.orderId());
        if (sport != null && !"CONFIRMED".equals(sport.status())) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,
                    "体育预订当前不可核销，状态为 " + sport.status());
        }
        MeetingState meeting = lockMeetingState(ticket.orderId());
        if (meeting != null && !List.of("PAID", "APPROVED").contains(meeting.status())) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,
                    "会议预订当前不可核销，状态为 " + meeting.status());
        }
        int changed = jdbcTemplate.update("""
                UPDATE bf_verification SET status='USED', verified_by=?, verified_at=CURRENT_TIMESTAMP,
                verify_channel=?,device_no=?
                WHERE id=? AND status='UNUSED'
                """, operatorId, verificationChannel, deviceNo, ticket.id());
        if (changed != 1) throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "电子票已被其他工作人员核销");
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log (operator_id, action, target_type, target_id, detail)
                VALUES (?, 'VERIFICATION_CONSUME', 'VERIFICATION', ?,
                        JSON_OBJECT('orderId', ?, 'operatorAccountType', ?, 'verificationChannel', ?))
                """, operatorId, String.valueOf(ticket.id()), ticket.orderId(), operator.accountType(), verificationChannel);
        if (meeting != null) {
            startMeetingUsage(meeting, ticket, operatorId, verificationChannel, deviceNo);
        } else if (sport == null) {
            completeOrderWhenAllUsed(ticket.orderId(), operatorId);
        } else {
            startSportUsage(sport, ticket.id(), operatorId, verificationChannel, deviceNo);
        }
        LocalDateTime verifiedAt = jdbcTemplate.queryForObject(
                "SELECT verified_at FROM bf_verification WHERE id=?", LocalDateTime.class, ticket.id());
        return new VerificationTicketResponse(ticket.id(), ticket.orderId(), ticket.orderNo(), ticket.orderItemId(),
                ticket.ticketNo(), ticket.resourceName(), ticket.resourceType(), null, null, "USED", ticket.serviceDate(), verifiedAt);
    }

    private List<VerificationTicketResponse> diningCredential(long orderId, long userId) {
        return jdbcTemplate.query("""
                SELECT r.id,r.reservation_no,r.fulfillment_type,r.reservation_date,r.status,r.verified_at,
                       r.order_id,o.order_no,i.id order_item_id,s.name store_name
                FROM bf_dining_reservation r JOIN bf_order o ON o.id=r.order_id
                JOIN bf_order_item i ON i.order_id=o.id JOIN bf_dining_store s ON s.id=r.store_id
                WHERE r.order_id=? AND r.user_id=? AND r.fulfillment_type='DINE_IN'
                  AND r.status NOT IN ('PENDING_PAYMENT','CANCELLED','REFUND_PENDING','REFUNDING','REFUNDED')
                """, (rs,n) -> {
            String code = diningCode(rs.getString("reservation_no"));
            String status = List.of("VERIFIED", "COMPLETED").contains(rs.getString("status")) ? "USED" : "UNUSED";
            return new VerificationTicketResponse(rs.getLong("id"),rs.getLong("order_id"),rs.getString("order_no"),
                    rs.getLong("order_item_id"),1,rs.getString("store_name") + "到店凭证","DINING_ORDER",
                    code,qrCodeService.asDataUrl(code),status,rs.getObject("reservation_date",LocalDate.class),
                    rs.getObject("verified_at",LocalDateTime.class));
        }, orderId, userId);
    }

    private List<VerificationTicketResponse> hotelCredential(AuthenticatedPrincipal principal, long orderId, long userId) {
        List<HotelCredentialHead> rows = jdbcTemplate.query("""
                SELECT r.id,r.check_in_date,o.order_no,i.id order_item_id,h.name hotel_name
                FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id
                JOIN bf_order_item i ON i.order_id=o.id JOIN bf_hotel h ON h.id=r.hotel_id
                WHERE r.order_id=? AND o.user_id=? AND o.status IN ('PAID','READY','APPROVED')
                """, (rs,n)->new HotelCredentialHead(rs.getLong("id"),rs.getObject("check_in_date",LocalDate.class),
                rs.getString("order_no"),rs.getLong("order_item_id"),rs.getString("hotel_name")),orderId,userId);
        if (rows.isEmpty()) return List.of();
        HotelCredentialHead head=rows.get(0);
        CheckInCredentialResponse value=hotelCredentialService.issue(principal,head.reservationId());
        return List.of(new VerificationTicketResponse(value.reservationId(),orderId,head.orderNo(),head.orderItemId(),1,
                head.hotelName()+"入住凭证","HOTEL_ROOM",value.checkInCode(),qrCodeService.asDataUrl(value.checkInCode()),
                "USED".equals(value.status())?"USED":"UNUSED",head.checkInDate(),null));
    }

    private String diningCode(String reservationNo) {
        return "BFDI-" + reservationNo.substring(Math.max(0,reservationNo.length()-10));
    }

    private SportState lockSportState(long orderId) {
        List<SportState> rows = jdbcTemplate.query(
                "SELECT id,status FROM bf_sport_booking WHERE order_id=? FOR UPDATE",
                (rs,n)->new SportState(rs.getLong("id"),rs.getString("status")), orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MeetingState lockMeetingState(long orderId) {
        List<MeetingState> rows = jdbcTemplate.query("""
                SELECT r.id,r.status,r.booking_type,r.attendees,r.meeting_subject,i.id order_item_id
                FROM bf_meeting_reservation r JOIN bf_order_item i ON i.order_id=r.order_id
                WHERE r.order_id=? FOR UPDATE
                """, (rs,n)->new MeetingState(rs.getLong("id"),rs.getString("status"),
                rs.getString("booking_type"),rs.getInt("attendees"),rs.getString("meeting_subject"),
                rs.getLong("order_item_id")), orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void startMeetingUsage(MeetingState meeting, LockedTicket ticket, long operatorId,
                                   String channel, String deviceNo) {
        int changed = jdbcTemplate.update("""
                UPDATE bf_meeting_reservation SET status='VERIFIED' WHERE id=? AND status IN ('PAID','APPROVED')
                """, meeting.id());
        if (changed != 1) throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "会议预订已被其他工作人员核销");
        jdbcTemplate.update("""
                INSERT INTO bf_meeting_booking
                (order_item_id,reservation_id,verification_id,attendee_count,meeting_subject,internal_booking,
                 status,checked_in_by,usage_started_at)
                VALUES (?,?,?,?,?,?,'CHECKED_IN',?,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE reservation_id=VALUES(reservation_id),verification_id=VALUES(verification_id),
                attendee_count=VALUES(attendee_count),meeting_subject=VALUES(meeting_subject),
                internal_booking=VALUES(internal_booking),status='CHECKED_IN',checked_in_by=VALUES(checked_in_by),
                usage_started_at=CURRENT_TIMESTAMP,usage_ended_at=NULL
                """, meeting.orderItemId(), meeting.id(), ticket.id(), meeting.attendees(), meeting.subject(),
                "INTERNAL".equals(meeting.type()) ? 1 : 0, operatorId);
        jdbcTemplate.update("""
                INSERT INTO bf_meeting_verification_log
                (reservation_id,verification_id,action,operator_id,verification_channel,device_no,
                 status_before,status_after,note)
                VALUES (?,?, 'CHECK_IN',?,?,?,?, 'VERIFIED','工作人员现场核销，会议开始使用')
                """, meeting.id(), ticket.id(), operatorId, channel, deviceNo, meeting.status());
        Integer checkInSequence = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_meeting_verification_log
                WHERE reservation_id=? AND action='CHECK_IN'
                """, Integer.class, meeting.id());
        jdbcTemplate.update("""
                INSERT INTO bf_meeting_reservation_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,?, 'VERIFIED',?,'工作人员现场核销，会议开始使用',?)
                """, meeting.id(), meeting.status(), operatorId,
                "MEETING-CHECK-IN:" + ticket.id() + ":" + checkInSequence);
    }

    private void startSportUsage(SportState sport, long verificationId, long operatorId,
                                 String channel, String deviceNo) {
        if (jdbcTemplate.update("""
                UPDATE bf_sport_booking SET status='IN_USE',checked_in_at=CURRENT_TIMESTAMP,verification_id=?
                WHERE id=? AND status='CONFIRMED'
                """, verificationId, sport.id()) != 1) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "体育预订已被其他工作人员核销");
        }
        jdbcTemplate.update("""
                INSERT INTO bf_sport_verification_log
                (booking_id,verification_id,operator_id,verification_channel,device_no,
                 booking_status_before,booking_status_after)
                VALUES (?,?,?,?,?,'CONFIRMED','IN_USE')
                """, sport.id(), verificationId, operatorId, channel, deviceNo);
        jdbcTemplate.update("""
                INSERT INTO bf_sport_booking_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                VALUES (?,'CONFIRMED','IN_USE',?,'工作人员扫码核销，场地开始使用',?)
                """, sport.id(), operatorId, "SPORT-CHECK-IN:" + verificationId);
    }

    private String ticketLockSelect() {
        return """
                SELECT v.id, v.ticket_no, v.status, i.id order_item_id,
                       COALESCE(package_item.component_sku_name,i.resource_name) resource_name,
                       COALESCE(package_item.component_resource_type,i.resource_type) resource_type,
                       i.service_date, i.time_slot,
                       JSON_UNQUOTE(JSON_EXTRACT(sku.attributes,'$.productKind')) product_kind,
                       o.id order_id, o.order_no, o.status order_status
                FROM bf_verification v
                JOIN bf_order_item i ON i.id = v.order_item_id
                JOIN bf_resource_sku sku ON sku.id = i.sku_id
                JOIN bf_order o ON o.id = i.order_id
                LEFT JOIN bf_order_package_item package_item ON package_item.id=v.order_package_item_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<LockedTicket> lockedTicketMapper() {
        return (rs, n) -> new LockedTicket(rs.getLong("id"), rs.getInt("ticket_no"),
                rs.getString("status"), rs.getLong("order_item_id"), rs.getString("resource_name"),
                rs.getString("resource_type"), rs.getObject("service_date", LocalDate.class),
                rs.getString("time_slot"), rs.getString("product_kind"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getString("order_status"));
    }

    static void validateExperienceTimeSlot(String productKind, LocalDate serviceDate,
                                           String timeSlot, LocalDateTime now) {
        if (!"EXPERIENCE".equalsIgnoreCase(productKind)) {
            return;
        }
        if (serviceDate == null) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "军事体验电子票缺少预约日期，无法核销");
        }
        Matcher matcher = TIME_SLOT_PATTERN.matcher(timeSlot == null ? "" : timeSlot);
        if (!matcher.matches()) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,
                    "军事体验预约时段格式无效，应为 HH:mm-HH:mm");
        }
        LocalTime startTime = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        LocalTime endTime = LocalTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
        if (!endTime.isAfter(startTime)) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT, "军事体验预约结束时间必须晚于开始时间");
        }
        LocalDateTime allowedFrom = serviceDate.atTime(startTime).minusMinutes(15);
        LocalDateTime allowedUntil = serviceDate.atTime(endTime).plusMinutes(15);
        if (now.isBefore(allowedFrom)) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,
                    "军事体验尚未到可核销时间，预约时段为 " + timeSlot + "，可提前15分钟核销");
        }
        if (now.isAfter(allowedUntil)) {
            throw new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,
                    "军事体验已超过可核销时间，预约时段为 " + timeSlot + "，结束后15分钟停止核销");
        }
    }

    private void completeOrderWhenAllUsed(long orderId, long operatorId) {
        Integer unused = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_verification v JOIN bf_order_item i ON i.id=v.order_item_id
                WHERE i.order_id=? AND v.status='UNUSED'
                """, Integer.class, orderId);
        if (unused != null && unused == 0) {
            int changed = jdbcTemplate.update(
                    "UPDATE bf_order SET status='COMPLETED' WHERE id=? AND status IN ('PAID','READY')", orderId);
            if (changed == 1) {
                jdbcTemplate.update("""
                        INSERT INTO bf_order_status_log
                        (order_id, from_status, to_status, operator_id, reason, idempotency_key)
                        VALUES (?, 'PAID', 'COMPLETED', ?, '全部电子票核销完成', ?)
                        """, orderId, operatorId, "VERIFY-COMPLETE:" + orderId);
            }
        }
    }

    private long requireDatabaseUser(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class,
                principal.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户尚无业务数据");
        return ids.get(0);
    }

    private long ensureDatabaseOperator(AuthenticatedPrincipal principal) {
        if ("ADMIN".equals(principal.accountType())) {
            try {
                long userId = Long.parseLong(principal.userId());
                Integer active = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM bf_user WHERE id=? AND status='ACTIVE'", Integer.class, userId);
                if (active == null || active == 0) throw new NumberFormatException();
                return userId;
            } catch (RuntimeException exception) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN, "核销人员账号无效或已停用");
            }
        }
        String openid = principal.databaseOpenId();
        jdbcTemplate.update("""
                INSERT INTO bf_user (wechat_openid, nickname, status)
                VALUES (?, ?, 'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)
                """, openid, principal.displayName());
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'", Long.class, openid);
        if (ids.isEmpty()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "操作账号已停用");
        }
        return ids.get(0);
    }

    private String ticketCode(String orderNo, long orderItemId, Long packageItemId, int ticketNo) {
        String payload = packageItemId == null
                ? orderNo + ":" + orderItemId + ":" + ticketNo
                : orderNo + ":" + orderItemId + ":P" + packageItemId + ":" + ticketNo;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12).toUpperCase(Locale.ROOT);
            return "BFHV-" + orderItemId + "-" + ticketNo + "-" + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成电子票", ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算核销码摘要", ex);
        }
    }

    private record IssueItem(long orderItemId, int quantity, String orderNo) {}
    private record PackageIssueItem(long packageItemId, long orderItemId, int quantity, String orderNo) {}
    private record LockedTicket(long id, int ticketNo, String status, long orderItemId,
                                String resourceName, String resourceType, LocalDate serviceDate,
                                String timeSlot, String productKind, long orderId, String orderNo,
                                String orderStatus) {}
    private record SportState(long id, String status) {}
    private record MeetingState(long id, String status, String type, int attendees,
                                String subject, long orderItemId) {}
    private record HotelCredentialHead(long reservationId,LocalDate checkInDate,String orderNo,
                                       long orderItemId,String hotelName) {}
    private record DiningCredential(long id,long orderId,String orderNo,long orderItemId,LocalDate reservationDate,
                                    String status,String fulfillmentType,String storeName) {}
}
