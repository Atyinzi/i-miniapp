package com.iminiapp.backend.gate.application;

import com.iminiapp.backend.gate.api.GateConsumeRequest;
import com.iminiapp.backend.gate.api.GateDecisionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GateAccessIdempotencyTests {
    @Test
    void exactEventRetryReturnsStoredDecisionBeforeReservingNonceOrConsumingAgain() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GateSecretCipher cipher = mock(GateSecretCipher.class);
        GateConsumeTransactionService consume = mock(GateConsumeTransactionService.class);
        GateEventRecorder recorder = mock(GateEventRecorder.class);
        String secret = "0123456789abcdef0123456789abcdef";
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "0123456789abcdef";
        String eventNo = "EVT-20260814-0001";
        String code = "BFHV-1-1-ABCDEF123456";
        String signature = GateSignature.calculate(secret, "GATE-01", timestamp, nonce, eventNo, code);
        GateDecisionResponse stored = new GateDecisionResponse(eventNo, true, "GRANTED", "ok",
                91L, 71L, LocalDateTime.now());

        when(jdbc.query(contains("FROM bf_gate_device"), any(RowMapper.class), eq("GATE-01")))
                .thenAnswer(this::deviceRow);
        when(cipher.decrypt(any(byte[].class))).thenReturn(secret);
        when(jdbc.queryForList(contains("FROM bf_gate_event"), eq(Integer.class), eq(3L), eq(eventNo)))
                .thenReturn(List.of(1));
        when(recorder.find(3L, eventNo, GateAccessService.sha256(code))).thenReturn(stored);

        GateDecisionResponse result = new GateAccessService(jdbc, cipher, consume, recorder)
                .consume("gate-01", String.valueOf(timestamp), nonce, signature,
                        new GateConsumeRequest(eventNo, code));

        assertSame(stored, result);
        verify(recorder).find(3L, eventNo, GateAccessService.sha256(code));
        verifyNoInteractions(consume);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private Object deviceRow(InvocationOnMock invocation) throws Exception {
        RowMapper<Object> mapper = invocation.getArgument(1);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(3L);
        when(rs.getString("device_no")).thenReturn("GATE-01");
        when(rs.getString("name")).thenReturn("North gate");
        when(rs.getString("venue_code")).thenReturn("WEAPON_CITY");
        when(rs.getBytes("secret_cipher")).thenReturn(new byte[]{1, 2, 3});
        return List.of(mapper.mapRow(rs, 0));
    }
}
