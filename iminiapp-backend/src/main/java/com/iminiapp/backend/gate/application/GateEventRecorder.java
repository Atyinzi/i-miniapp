package com.iminiapp.backend.gate.application;

import com.iminiapp.backend.gate.api.GateDecisionResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GateEventRecorder {
    private final JdbcTemplate jdbc;

    public GateEventRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GateDecisionResponse denied(long deviceId, String eventNo, String codeHash, String message) {
        String safeMessage = safeMessage(message);
        try {
            jdbc.update("""
                    INSERT INTO bf_gate_event
                    (device_id,event_no,code_hash,decision,message,happened_at)
                    VALUES (?,?,?,'DENIED',?,CURRENT_TIMESTAMP)
                    """, deviceId, eventNo, codeHash, safeMessage);
        } catch (DuplicateKeyException ignored) {
            // 同一设备事件号只记录一次；并发重试返回第一次决策。
        }
        return find(deviceId, eventNo, codeHash);
    }

    @Transactional(readOnly = true)
    public GateDecisionResponse find(long deviceId, String eventNo, String codeHash) {
        List<StoredEvent> rows = jdbc.query("""
                SELECT event_no,code_hash,verification_ref,order_id,decision,message,created_at
                FROM bf_gate_event WHERE device_id=? AND event_no=?
                """, (rs, row) -> new StoredEvent(rs.getString("event_no"), rs.getString("code_hash"),
                rs.getObject("verification_ref", Long.class), rs.getObject("order_id", Long.class),
                rs.getString("decision"), rs.getString("message"),
                rs.getObject("created_at", LocalDateTime.class)), deviceId, eventNo);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "闸机事件记录失败");
        StoredEvent value = rows.get(0);
        if (!value.codeHash().equals(codeHash)) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "同一事件号不能用于不同核销码");
        }
        return new GateDecisionResponse(value.eventNo(), "GRANTED".equals(value.decision()), value.decision(),
                value.message(), value.verificationId(), value.orderId(), value.decidedAt());
    }

    private String safeMessage(String value) {
        String result = value == null || value.isBlank() ? "核销未通过" : value.trim();
        return result.length() <= 255 ? result : result.substring(0, 255);
    }

    private record StoredEvent(String eventNo, String codeHash, Long verificationId, Long orderId,
                               String decision, String message, LocalDateTime decidedAt) { }
}
