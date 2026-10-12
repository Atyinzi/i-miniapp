package com.iminiapp.backend.member.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.member.api.PersonalDataModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.security.SensitiveDataCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class PersonalDataService {
    private static final Pattern MOBILE = Pattern.compile("^1\\d{10}$");
    private static final Pattern ID_CARD = Pattern.compile("^\\d{17}[0-9X]$");
    private final JdbcTemplate jdbc;
    private final SensitiveDataCipher cipher;

    public PersonalDataService(JdbcTemplate jdbc, SensitiveDataCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> addresses(AuthenticatedPrincipal principal) {
        return addresses(userId(principal));
    }

    @Transactional
    public List<AddressResponse> createAddress(AuthenticatedPrincipal principal, AddressRequest request) {
        long userId = userId(principal); AddressData data = address(request);
        if (data.isDefault() || countAddresses(userId) == 0) clearDefaults(userId);
        jdbc.update("""
                INSERT INTO bf_user_address(user_id,receiver_name,receiver_mobile,province,city,district,detail_address,is_default)
                VALUES (?,?,?,'','','',?,?)
                """, userId, data.name(), data.phone(), data.detail(), data.isDefault() || countAddresses(userId) == 0);
        return addresses(userId);
    }

    @Transactional
    public List<AddressResponse> updateAddress(AuthenticatedPrincipal principal, long id, AddressRequest request) {
        long userId = userId(principal); requireAddress(userId, id); AddressData data = address(request);
        if (data.isDefault()) clearDefaults(userId);
        jdbc.update("UPDATE bf_user_address SET receiver_name=?,receiver_mobile=?,detail_address=?,is_default=? WHERE id=? AND user_id=? AND deleted=0",
                data.name(), data.phone(), data.detail(), data.isDefault(), id, userId);
        ensureDefault(userId); return addresses(userId);
    }

    @Transactional
    public List<AddressResponse> defaultAddress(AuthenticatedPrincipal principal, long id) {
        long userId = userId(principal); requireAddress(userId, id); clearDefaults(userId);
        jdbc.update("UPDATE bf_user_address SET is_default=1 WHERE id=? AND user_id=? AND deleted=0", id, userId);
        return addresses(userId);
    }

    @Transactional
    public List<AddressResponse> deleteAddress(AuthenticatedPrincipal principal, long id) {
        long userId = userId(principal); requireAddress(userId, id);
        jdbc.update("UPDATE bf_user_address SET deleted=1,is_default=0 WHERE id=? AND user_id=?", id, userId);
        ensureDefault(userId); return addresses(userId);
    }

    @Transactional(readOnly = true)
    public List<TravelerResponse> travelers(AuthenticatedPrincipal principal) { return travelers(userId(principal)); }

    @Transactional
    public List<TravelerResponse> createTraveler(AuthenticatedPrincipal principal, TravelerRequest request) {
        long userId = userId(principal); TravelerData data = traveler(request); rejectDuplicate(userId, data.idCard(), null);
        jdbc.update("INSERT INTO bf_common_traveler(user_id,traveler_name,id_card_cipher,id_card_hash,mobile_cipher,mobile_hash) VALUES (?,?,?,?,?,?)",
                userId, data.name(), cipher.encrypt(data.idCard()), cipher.searchHash(data.idCard()), cipher.encrypt(data.phone()), cipher.searchHash(data.phone()));
        return travelers(userId);
    }

    @Transactional
    public List<TravelerResponse> updateTraveler(AuthenticatedPrincipal principal, long id, TravelerRequest request) {
        long userId = userId(principal); requireTraveler(userId, id); TravelerData data = traveler(request); rejectDuplicate(userId, data.idCard(), id);
        jdbc.update("UPDATE bf_common_traveler SET traveler_name=?,id_card_cipher=?,id_card_hash=?,mobile_cipher=?,mobile_hash=? WHERE id=? AND user_id=? AND deleted=0",
                data.name(), cipher.encrypt(data.idCard()), cipher.searchHash(data.idCard()), cipher.encrypt(data.phone()), cipher.searchHash(data.phone()), id, userId);
        return travelers(userId);
    }

    @Transactional
    public List<TravelerResponse> deleteTraveler(AuthenticatedPrincipal principal, long id) {
        long userId = userId(principal); requireTraveler(userId, id);
        jdbc.update("UPDATE bf_common_traveler SET deleted=1 WHERE id=? AND user_id=?", id, userId);
        return travelers(userId);
    }

    private List<AddressResponse> addresses(long userId) {
        return jdbc.query("SELECT id,receiver_name,receiver_mobile,detail_address,is_default FROM bf_user_address WHERE user_id=? AND deleted=0 ORDER BY is_default DESC,id DESC",
                (rs, n) -> new AddressResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)), userId);
    }
    private List<TravelerResponse> travelers(long userId) {
        return jdbc.query("SELECT id,traveler_name,id_card_cipher,mobile_cipher FROM bf_common_traveler WHERE user_id=? AND deleted=0 ORDER BY id DESC",
                (rs, n) -> travelerResponse(rs.getLong(1), rs.getString(2), cipher.decrypt(rs.getBytes(3)), cipher.decrypt(rs.getBytes(4))), userId);
    }
    private TravelerResponse travelerResponse(long id, String name, String idCard, String phone) {
        return new TravelerResponse(id, name, first(name), idCard, phone,
                idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4),
                phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4));
    }
    private long userId(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM bf_user WHERE wechat_openid=? AND status='ACTIVE'", Long.class, principal.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户不存在"); return ids.get(0);
    }
    private AddressData address(AddressRequest r) {
        if (r == null) throw invalid("地址信息不能为空");
        String name = required(r.name(), 64, "联系人"); String phone = required(r.phone(), 32, "手机号"); String detail = required(r.detail(), 255, "详细地址");
        if (!MOBILE.matcher(phone).matches()) throw invalid("手机号格式不正确");
        return new AddressData(name, phone, detail, Boolean.TRUE.equals(r.isDefault()));
    }
    private TravelerData traveler(TravelerRequest r) {
        if (r == null) throw invalid("旅客信息不能为空");
        String name = required(r.name(), 64, "姓名"); String idCard = required(r.idCard(), 18, "身份证号").toUpperCase(); String phone = required(r.phone(), 32, "手机号");
        if (!ID_CARD.matcher(idCard).matches()) throw invalid("身份证号格式不正确");
        if (!MOBILE.matcher(phone).matches()) throw invalid("手机号格式不正确");
        return new TravelerData(name, idCard, phone);
    }
    private void rejectDuplicate(long userId, String idCard, Long excludedId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bf_common_traveler WHERE user_id=? AND id_card_hash=? AND deleted=0" + (excludedId == null ? "" : " AND id<>?"), Integer.class,
                excludedId == null ? new Object[]{userId, cipher.searchHash(idCard)} : new Object[]{userId, cipher.searchHash(idCard), excludedId});
        if (count != null && count > 0) throw new BusinessException(CommonErrorCode.CONFLICT, "该旅客已经存在");
    }
    private void requireAddress(long userId, long id) { requireExists("SELECT COUNT(*) FROM bf_user_address WHERE id=? AND user_id=? AND deleted=0", id, userId, "地址不存在"); }
    private void requireTraveler(long userId, long id) { requireExists("SELECT COUNT(*) FROM bf_common_traveler WHERE id=? AND user_id=? AND deleted=0", id, userId, "常用旅客不存在"); }
    private void requireExists(String sql, long id, long userId, String message) { Integer count=jdbc.queryForObject(sql,Integer.class,id,userId); if(count==null||count==0)throw new BusinessException(CommonErrorCode.NOT_FOUND,message); }
    private int countAddresses(long userId) { Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM bf_user_address WHERE user_id=? AND deleted=0",Integer.class,userId);return count==null?0:count; }
    private void clearDefaults(long userId) { jdbc.update("UPDATE bf_user_address SET is_default=0 WHERE user_id=? AND deleted=0",userId); }
    private void ensureDefault(long userId) { if(countAddresses(userId)>0){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM bf_user_address WHERE user_id=? AND deleted=0 AND is_default=1",Integer.class,userId);if(count==null||count==0)jdbc.update("UPDATE bf_user_address SET is_default=1 WHERE user_id=? AND deleted=0 ORDER BY id DESC LIMIT 1",userId);} }
    private String required(String value,int max,String name){if(!StringUtils.hasText(value)||value.trim().length()>max)throw invalid(name+"不能为空且最长"+max+"字符");return value.trim();}
    private String first(String value){return value.substring(0,Math.min(1,value.length()));}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private record AddressData(String name,String phone,String detail,boolean isDefault){}
    private record TravelerData(String name,String idCard,String phone){}
}
