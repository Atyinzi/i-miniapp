package com.iminiapp.backend.sport;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.sport.api.SportVenueModels.*;
import com.iminiapp.backend.sport.application.SportVenueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.task.scheduling.enabled=false"
        })
@Transactional
class SportVenueServiceIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "sport-venue-admin", "体育场馆管理员", "ADMIN", List.of("ROLE_ADMIN"));

    @Autowired private SportVenueService service;

    @Test
    void 可维护场馆场地开放时段和淡旺季价格() {
        VenueResponse venue = service.createVenue(ADMIN, new VenueUpsertRequest(
                "IT_SPORT_CENTER", "i某小程序测试体育中心", "测试路1号", "0472-1234567", "集成测试场馆",
                LocalTime.of(8, 0), LocalTime.of(22, 0), "ACTIVE"));
        CourtTypeResponse type = service.createCourtType(ADMIN, new CourtTypeUpsertRequest(
                venue.id(), "BADMINTON", "羽毛球场", "标准羽毛球场", 6000L, "ACTIVE"));
        CourtResponse court = service.createCourt(ADMIN, new CourtUpsertRequest(
                venue.id(), type.id(), "B01", "羽毛球1号场", "一层东区", 4, "ACTIVE"));
        OpeningRuleResponse opening = service.createOpeningRule(ADMIN, new OpeningRuleUpsertRequest(
                court.id(), 1, LocalTime.of(9, 0), LocalTime.of(21, 0), 60, "ACTIVE"));
        PriceRuleResponse season = service.createPriceRule(ADMIN, new PriceRuleUpsertRequest(
                venue.id(), type.id(), null, "IT_SPORT_SUMMER", "暑期旺季价", "SEASON",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), null,
                LocalTime.of(18, 0), LocalTime.of(21, 0), 8000L, 20, "ACTIVE"));
        PriceRuleResponse holiday = service.createPriceRule(ADMIN, new PriceRuleUpsertRequest(
                venue.id(), type.id(), court.id(), "IT_SPORT_HOLIDAY", "国庆节价格", "HOLIDAY",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), null,
                null, null, 10000L, 100, "ACTIVE"));

        VenueResponse refreshed = service.venues("ACTIVE").stream().filter(v -> v.id() == venue.id()).findFirst().orElseThrow();
        assertEquals(1, refreshed.courtTypeCount());
        assertEquals(1, refreshed.courtCount());
        assertEquals(1, service.courtTypes(venue.id(), "ACTIVE").size());
        assertEquals(1, service.courts(venue.id(), type.id(), "ACTIVE").size());
        assertEquals(60, opening.slotMinutes());
        assertEquals(8000L, season.priceCent());
        assertEquals(court.id(), holiday.courtId());
    }

    @Test
    void 拒绝重叠时段和跨场馆价格范围() {
        VenueResponse first = service.createVenue(ADMIN, new VenueUpsertRequest(
                "IT_SPORT_FIRST", "第一体育馆", "测试地址A", null, null,
                LocalTime.of(8, 0), LocalTime.of(22, 0), "ACTIVE"));
        VenueResponse second = service.createVenue(ADMIN, new VenueUpsertRequest(
                "IT_SPORT_SECOND", "第二体育馆", "测试地址B", null, null,
                LocalTime.of(9, 0), LocalTime.of(21, 0), "ACTIVE"));
        CourtTypeResponse type = service.createCourtType(ADMIN, new CourtTypeUpsertRequest(
                first.id(), "TABLE_TENNIS", "乒乓球场", null, 3000L, "ACTIVE"));
        CourtResponse court = service.createCourt(ADMIN, new CourtUpsertRequest(
                first.id(), type.id(), "T01", "乒乓球1号台", null, 2, "ACTIVE"));
        service.createOpeningRule(ADMIN, new OpeningRuleUpsertRequest(
                court.id(), 2, LocalTime.of(10, 0), LocalTime.of(12, 0), 30, "ACTIVE"));

        assertThrows(BusinessException.class, () -> service.createOpeningRule(ADMIN, new OpeningRuleUpsertRequest(
                court.id(), 2, LocalTime.of(11, 30), LocalTime.of(13, 0), 30, "ACTIVE")));
        assertThrows(BusinessException.class, () -> service.createOpeningRule(ADMIN, new OpeningRuleUpsertRequest(
                court.id(), 3, LocalTime.of(7, 0), LocalTime.of(9, 0), 30, "ACTIVE")));
        assertThrows(BusinessException.class, () -> service.createPriceRule(ADMIN, new PriceRuleUpsertRequest(
                second.id(), type.id(), court.id(), "IT_SPORT_INVALID", "错误范围", "BASE",
                null, null, null, null, null, 3000L, 0, "ACTIVE")));
    }
}
