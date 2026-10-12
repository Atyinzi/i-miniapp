package com.iminiapp.backend.hotel.application;

import com.iminiapp.backend.hotel.api.HotelOperationModels.CheckInCredentialResponse;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.security.SensitiveDataCipher;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class HotelCheckinCredentialService {
    private final JdbcTemplate jdbc;
    private final byte[] signingKey;
    private final SensitiveDataCipher cipher;
    public HotelCheckinCredentialService(JdbcTemplate jdbc, SensitiveDataCipher cipher,
            @Value("${app.hotel.checkin-signing-key}") String key) {
        this.jdbc=jdbc;this.cipher=cipher;this.signingKey=key.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public CheckInCredentialResponse issue(AuthenticatedPrincipal principal,long reservationId){
        long userId=requireUser(principal);
        CredentialHead head=jdbc.query("""
                SELECT r.id,r.reservation_no,r.check_in_date,r.check_out_date,r.stay_status,o.status order_status
                FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id
                WHERE r.id=? AND o.user_id=? FOR UPDATE
                """,(rs,n)->new CredentialHead(rs.getLong(1),rs.getString(2),rs.getObject(3,LocalDate.class),
                rs.getObject(4,LocalDate.class),rs.getString(5),rs.getString(6)),reservationId,userId).stream().findFirst()
                .orElseThrow(()->notFound("住宿订单不存在或无权访问"));
        if(!List.of("PAID","READY","APPROVED").contains(head.orderStatus()))
            throw conflict("住宿订单支付完成后才能生成入住码");
        if(List.of("CHECKED_OUT","NO_SHOW").contains(head.stayStatus()))throw conflict("住宿订单已结束，不能生成入住码");
        String code=code(head.reservationNo());String hash=sha256(code);
        LocalDateTime validFrom=head.checkIn().minusDays(1).atStartOfDay();
        LocalDateTime validUntil=head.checkOut().atTime(23,59,59);
        jdbc.update("""
                INSERT INTO bf_hotel_checkin_credential(reservation_id,code_hash,status,valid_from,valid_until)
                VALUES (?,?,'ACTIVE',?,?)
                ON DUPLICATE KEY UPDATE code_hash=VALUES(code_hash),
                  status=CASE WHEN status='USED' THEN status ELSE 'ACTIVE' END,
                  valid_from=VALUES(valid_from),valid_until=VALUES(valid_until)
                """,reservationId,hash,validFrom,validUntil);
        String status=jdbc.queryForObject("SELECT status FROM bf_hotel_checkin_credential WHERE reservation_id=?",String.class,reservationId);
        return new CheckInCredentialResponse(reservationId,head.reservationNo(),code,status,validFrom,validUntil);
    }

    @Transactional(readOnly=true)
    public long requireReservationByCode(String code){
        if(!StringUtils.hasText(code))throw invalid("入住码不能为空");
        Credential credential=jdbc.query("""
                SELECT reservation_id,status,valid_from,valid_until FROM bf_hotel_checkin_credential
                WHERE code_hash=?
                """,(rs,n)->new Credential(rs.getLong(1),rs.getString(2),rs.getObject(3,LocalDateTime.class),
                rs.getObject(4,LocalDateTime.class)),sha256(code.trim().toUpperCase(Locale.ROOT))).stream().findFirst()
                .orElseThrow(()->notFound("入住码不存在"));
        LocalDateTime now=LocalDateTime.now();
        if(!"ACTIVE".equals(credential.status()))throw conflict("入住码已使用或失效");
        if(now.isBefore(credential.validFrom())||now.isAfter(credential.validUntil()))throw conflict("入住码不在有效期内");
        return credential.reservationId();
    }

    @Transactional
    public void consume(long reservationId){
        int changed=jdbc.update("""
                UPDATE bf_hotel_checkin_credential SET status='USED',used_at=CURRENT_TIMESTAMP
                WHERE reservation_id=? AND status='ACTIVE'
                """,reservationId);
        if(changed==0){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM bf_hotel_checkin_credential WHERE reservation_id=?",Integer.class,reservationId);if(count!=null&&count>0)throw conflict("入住码已使用或失效");}
    }

    public long findReservationByIdCard(String idNo){
        if(!StringUtils.hasText(idNo))throw invalid("证件号码不能为空");
        List<Long> ids=jdbc.queryForList("""
                SELECT r.id FROM bf_hotel_reservation r JOIN bf_hotel_reservation_guest g ON g.reservation_id=r.id
                JOIN bf_order o ON o.id=r.order_id
                WHERE g.id_no_hash=? AND r.stay_status='BOOKED' AND o.status IN ('PAID','READY','APPROVED')
                  AND r.check_in_date<=CURRENT_DATE AND r.check_out_date>CURRENT_DATE
                ORDER BY r.check_in_date,r.id
                """,Long.class,cipher.searchHash(idNo.trim()));
        if(ids.isEmpty())throw notFound("未找到今日可入住的住宿订单");
        if(ids.size()>1)throw conflict("证件号码对应多个可入住订单，请使用订单号或入住码办理");
        return ids.get(0);
    }
    private String code(String reservationNo){String signature=hmac(reservationNo).substring(0,12).toUpperCase(Locale.ROOT);return "HC-"+reservationNo+"-"+signature;}
    private String hmac(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("无法生成住宿入住码",ex);}}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("无法计算入住码摘要",ex);}}
    private long requireUser(AuthenticatedPrincipal principal){List<Long> ids=jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,principal.databaseOpenId());if(ids.isEmpty())throw notFound("当前用户尚无住宿订单");return ids.get(0);}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private BusinessException notFound(String message){return new BusinessException(CommonErrorCode.NOT_FOUND,message);}
    private BusinessException conflict(String message){return new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,message);}
    private record CredentialHead(long id,String reservationNo,LocalDate checkIn,LocalDate checkOut,String stayStatus,String orderStatus){}
    private record Credential(long reservationId,String status,LocalDateTime validFrom,LocalDateTime validUntil){}
}
