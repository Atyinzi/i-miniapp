package com.iminiapp.backend.hotel;

import com.iminiapp.backend.hotel.api.HotelModels.*;
import com.iminiapp.backend.hotel.application.HotelManagementService;
import com.iminiapp.backend.hotel.application.HotelQueryService;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.task.scheduling.enabled=false"
})
@Transactional
class HotelManagementServiceIT {
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            "hotel-admin", "住宿管理员", "ADMIN", List.of("ROLE_ADMIN"));
    @Autowired HotelManagementService management;
    @Autowired HotelQueryService query;

    @Test
    void 可维护房型设施图片节假日价格和库存日历() {
        HotelResponse hotel = management.createHotel(ADMIN, new HotelUpsertRequest(
                "IT_NORTH_HOTEL", "i某小程序测试酒店", "包头市测试路1号", "0472-1234567", "集成测试酒店",
                LocalTime.of(14, 0), LocalTime.NOON, "ACTIVE",
                List.of(new ImageInput("https://example.com/hotel.jpg", "COVER", 0, true))));
        RoomTypeResponse roomType = management.createRoomType(ADMIN, new RoomTypeUpsertRequest(
                hotel.id(), "DELUXE_TWIN", "豪华双床房", "双床", new BigDecimal("36.50"), 2, 10, 2,
                38800L, "含双早", "https://example.com/room.jpg", "ACTIVE",
                List.of(new FacilityInput("WIFI", "无线网络", 1), new FacilityInput("BREAKFAST", "双早", 2)),
                List.of(new ImageInput("https://example.com/room-1.jpg", "GALLERY", 1, true))));
        LocalDate start = LocalDate.now().plusDays(10);
        management.createPriceRule(ADMIN, new PriceRuleUpsertRequest(roomType.id(), "IT_HOLIDAY", "测试节假日价",
                "HOLIDAY", start.plusDays(1), start.plusDays(2), List.of(), 58800L, 100, "ACTIVE"));

        CalendarGenerateResponse generated = management.generateCalendar(ADMIN,
                new CalendarGenerateRequest(roomType.id(), start, start.plusDays(3), 10));
        List<InventoryDayResponse> calendar = management.calendar(roomType.id(), start, start.plusDays(3));
        List<PublicRoomType> available = query.roomTypes(hotel.id(), start, start.plusDays(3), 2);

        assertEquals(4, generated.generatedDays());
        assertEquals(List.of(38800L, 58800L, 58800L, 38800L),
                calendar.stream().map(InventoryDayResponse::priceCent).toList());
        assertEquals(2, roomType.facilities().size());
        assertEquals(1, available.size());
        assertEquals((38800L + 58800L + 58800L) * 2, available.get(0).stayAmountCent());
        assertEquals(10, available.get(0).minimumAvailableQuantity());
    }
}
