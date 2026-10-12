package com.iminiapp.backend.gate.application;

import com.iminiapp.backend.gate.api.GateDecisionResponse;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.verification.api.VerificationTicketResponse;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GateConsumeTransactionService {
    private final JdbcTemplate jdbc;
    private final VerificationTicketService verificationService;

    public GateConsumeTransactionService(JdbcTemplate jdbc, VerificationTicketService verificationService) {
        this.jdbc = jdbc;
        this.verificationService = verificationService;
    }

    @Transactional
    public GateDecisionResponse consume(long deviceId, String deviceNo, String deviceName, String venueCode,
                                        String eventNo, String normalizedCode, String codeHash) {
        requireVenueMatch(normalizedCode, venueCode);
        AuthenticatedPrincipal devicePrincipal = new AuthenticatedPrincipal(
                "gate-device:" + deviceId, deviceName, "GATE_DEVICE", List.of("GATE_DEVICE"),
                "GATE", deviceNo, List.of());
        VerificationTicketResponse ticket = verificationService.consume(devicePrincipal, normalizedCode, deviceNo);
        try {
            jdbc.update("""
                    INSERT INTO bf_gate_event
                    (device_id,event_no,code_hash,verification_ref,order_id,decision,message,happened_at)
                    VALUES (?,?,?,?,?,'GRANTED','核销通过',CURRENT_TIMESTAMP)
                    """, deviceId, eventNo, codeHash, ticket.verificationId(), ticket.orderId());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "闸机事件已处理");
        }
        LocalDateTime decidedAt = jdbc.queryForObject(
                "SELECT created_at FROM bf_gate_event WHERE device_id=? AND event_no=?",
                LocalDateTime.class, deviceId, eventNo);
        return new GateDecisionResponse(eventNo, true, "GRANTED", "核销通过",
                ticket.verificationId(), ticket.orderId(), decidedAt);
    }

    private void requireVenueMatch(String normalizedCode, String venueCode) {
        List<String> siteCodes = jdbc.query("""
                SELECT DISTINCT site_code FROM (
                  SELECT site.site_code
                  FROM bf_verification verification
                  JOIN bf_order_item item ON item.id=verification.order_item_id
                  JOIN bf_resource_sku sku ON sku.id=item.sku_id
                  JOIN bf_resource resource ON resource.id=sku.resource_id
                  LEFT JOIN bf_business_site site ON site.id=resource.site_id
                  WHERE verification.verification_code_hash=SHA2(?,256)
                  UNION ALL
                  SELECT store.store_code
                  FROM bf_dining_reservation reservation
                  JOIN bf_dining_store store ON store.id=reservation.store_id
                  WHERE reservation.verification_code_hash=SHA2(?,256)
                ) allowed_sites WHERE site_code IS NOT NULL
                """, (rs, row) -> rs.getString(1), normalizedCode, normalizedCode);
        if (siteCodes.isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "核销码不存在或未绑定业务场馆");
        }
        if (!venueMatches(siteCodes, venueCode)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "该闸机无权核销此场馆凭证");
        }
    }

    static boolean venueMatches(List<String> allowedSiteCodes, String deviceVenueCode) {
        if (allowedSiteCodes == null || allowedSiteCodes.isEmpty()
                || deviceVenueCode == null || deviceVenueCode.isBlank()) return false;
        return allowedSiteCodes.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(deviceVenueCode::equalsIgnoreCase);
    }
}
