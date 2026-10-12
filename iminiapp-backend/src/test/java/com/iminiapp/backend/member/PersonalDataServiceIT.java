package com.iminiapp.backend.member;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.member.api.PersonalDataModels.AddressRequest;
import com.iminiapp.backend.member.api.PersonalDataModels.TravelerRequest;
import com.iminiapp.backend.member.application.PersonalDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.task.scheduling.enabled=false")
@Transactional
class PersonalDataServiceIT {
    @Autowired PersonalDataService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 地址和常用旅客完整持久化且敏感字段加密() {
        String openId = "personal-data-" + UUID.randomUUID();
        jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,'资料测试用户','ACTIVE')", openId);
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "personal-data-user", "资料测试用户", "USER", List.of("ROLE_USER"), "WECHAT", openId);

        var addresses = service.createAddress(principal, new AddressRequest("张三", "13800138000", "包头市测试路1号", false));
        assertEquals(1, addresses.size());
        assertTrue(addresses.get(0).isDefault());
        addresses = service.createAddress(principal, new AddressRequest("李四", "13900139000", "包头市测试路2号", true));
        assertEquals(2, addresses.size());
        assertEquals("李四", addresses.get(0).name());
        addresses = service.deleteAddress(principal, addresses.get(0).id());
        assertEquals(1, addresses.size());
        assertTrue(addresses.get(0).isDefault());

        var travelers = service.createTraveler(principal,
                new TravelerRequest("王五", "110101199001011234", "13700137000"));
        assertEquals(1, travelers.size());
        assertEquals("110101********1234", travelers.get(0).maskedIdCard());
        assertEquals("137****7000", travelers.get(0).maskedPhone());
        byte[] encrypted = jdbc.queryForObject("SELECT id_card_cipher FROM bf_common_traveler WHERE id=?", byte[].class, travelers.get(0).id());
        assertNotNull(encrypted);
        assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains("110101199001011234"));
        assertTrue(service.deleteTraveler(principal, travelers.get(0).id()).isEmpty());
    }
}
