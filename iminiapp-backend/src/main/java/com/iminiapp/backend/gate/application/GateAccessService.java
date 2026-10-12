package com.iminiapp.backend.gate.application;

import com.iminiapp.backend.gate.api.GateConsumeRequest;
import com.iminiapp.backend.gate.api.GateDecisionResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class GateAccessService {
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final GateSecretCipher cipher;
    private final GateConsumeTransactionService consumeTransaction;
    private final GateEventRecorder eventRecorder;

    public GateAccessService(JdbcTemplate jdbc, GateSecretCipher cipher,
                             GateConsumeTransactionService consumeTransaction,
                             GateEventRecorder eventRecorder) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.consumeTransaction = consumeTransaction;
        this.eventRecorder = eventRecorder;
    }

    public GateDecisionResponse consume(String rawDeviceNo, String timestampHeader, String rawNonce,
                                        String rawSignature, GateConsumeRequest request) {
        RequestValues values = validateRequest(rawDeviceNo, timestampHeader, rawNonce, rawSignature, request);
        DeviceCredential device = activeDevice(values.deviceNo());
        verifySignature(device, values);
        List<Integer> existing = jdbc.queryForList(
                "SELECT 1 FROM bf_gate_event WHERE device_id=? AND event_no=?", Integer.class,
                device.id(), values.eventNo());
        if (!existing.isEmpty()) return eventRecorder.find(device.id(), values.eventNo(), values.codeHash());
        reserveNonce(device.id(), values.nonce());
        jdbc.update("UPDATE bf_gate_device SET last_seen_at=CURRENT_TIMESTAMP WHERE id=?", device.id());

        try {
            return consumeTransaction.consume(device.id(), device.deviceNo(), device.name(), device.venueCode(), values.eventNo(),
                    values.normalizedCode(), values.codeHash());
        } catch (BusinessException ex) {
            return eventRecorder.denied(device.id(), values.eventNo(), values.codeHash(), ex.getMessage());
        }
    }

    private RequestValues validateRequest(String rawDeviceNo, String timestampHeader, String rawNonce,
                                           String rawSignature, GateConsumeRequest request) {
        String deviceNo = required(rawDeviceNo, 64, "设备编号").toUpperCase(Locale.ROOT);
        if (!deviceNo.matches("[A-Z0-9_-]+")) throw invalid("设备编号格式不正确");
        if (request == null) throw invalid("闸机请求不能为空");
        String eventNo = required(request.eventNo(), 64, "事件号");
        if (eventNo.length() < 8 || !eventNo.matches("[A-Za-z0-9:_-]+")) throw invalid("事件号格式不正确");
        String normalizedCode = required(request.code(), 160, "核销码").toUpperCase(Locale.ROOT);
        String nonce = required(rawNonce, 64, "随机数");
        if (nonce.length() < 16 || !nonce.matches("[A-Za-z0-9_-]+")) throw invalid("随机数格式不正确");
        String signature = required(rawSignature, 128, "请求签名").toLowerCase(Locale.ROOT);
        if (!signature.matches("[0-9a-f]{64}")) throw unauthorized();
        long timestamp;
        try { timestamp = Long.parseLong(required(timestampHeader, 20, "时间戳")); }
        catch (NumberFormatException ex) { throw unauthorized(); }
        long now = Instant.now().getEpochSecond();
        if (timestamp < now - MAX_CLOCK_SKEW_SECONDS || timestamp > now + MAX_CLOCK_SKEW_SECONDS) throw unauthorized();
        return new RequestValues(deviceNo, timestamp, nonce, signature, eventNo, normalizedCode,
                sha256(normalizedCode));
    }

    private DeviceCredential activeDevice(String deviceNo) {
        List<DeviceCredential> rows = jdbc.query("""
                SELECT id,device_no,name,venue_code,secret_cipher FROM bf_gate_device
                WHERE device_no=? AND status='ACTIVE' AND venue_code IS NOT NULL AND TRIM(venue_code)<>''
                """, (rs, row) -> new DeviceCredential(rs.getLong("id"), rs.getString("device_no"),
                rs.getString("name"), rs.getString("venue_code"), rs.getBytes("secret_cipher")), deviceNo);
        if (rows.isEmpty()) throw unauthorized();
        return rows.get(0);
    }

    private void verifySignature(DeviceCredential device, RequestValues values) {
        String secret = cipher.decrypt(device.encryptedSecret());
        String expected = GateSignature.calculate(secret, values.deviceNo(), values.timestamp(), values.nonce(),
                values.eventNo(), values.normalizedCode());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                values.signature().getBytes(StandardCharsets.US_ASCII))) throw unauthorized();
    }

    private void reserveNonce(long deviceId, String nonce) {
        jdbc.update("DELETE FROM bf_gate_request_nonce WHERE device_id=? AND nonce=? AND expires_at<CURRENT_TIMESTAMP",
                deviceId, nonce);
        try {
            jdbc.update("INSERT INTO bf_gate_request_nonce(device_id,nonce,expires_at) " +
                    "VALUES (?,?,DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE))", deviceId, nonce);
        } catch (DuplicateKeyException ex) {
            throw unauthorized();
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算核销码摘要", ex);
        }
    }

    private String required(String value, int max, String label) {
        if (!StringUtils.hasText(value) || value.trim().length() > max) {
            throw invalid(label + "不能为空且最长" + max + "字符");
        }
        return value.trim();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
    }

    private BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED, "闸机设备认证失败");
    }

    private record DeviceCredential(long id, String deviceNo, String name, String venueCode, byte[] encryptedSecret) { }
    private record RequestValues(String deviceNo, long timestamp, String nonce, String signature,
                                 String eventNo, String normalizedCode, String codeHash) { }
}
