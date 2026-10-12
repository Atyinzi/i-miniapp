package com.iminiapp.backend.hotel;

import com.iminiapp.backend.hotel.application.HotelPricingPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HotelPricingPolicyTests {
    @Test
    void 节假日规则按优先级覆盖淡旺季和基础价() {
        LocalDate nationalDay = LocalDate.of(2026, 10, 1);
        List<HotelPricingPolicy.PriceCandidate> rules = List.of(
                new HotelPricingPolicy.PriceCandidate(1, "旺季价", LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 10, 7), Set.of(), 50000, 20),
                new HotelPricingPolicy.PriceCandidate(2, "国庆价", LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 7), Set.of(), 68800, 100));

        HotelPricingPolicy.ResolvedPrice price = HotelPricingPolicy.resolve(nationalDay, 38800, rules);

        assertEquals(68800, price.priceCent());
        assertEquals(2L, price.ruleId());
        assertEquals("RULE", price.sourceType());
    }

    @Test
    void 未命中规则时使用房型基础价() {
        HotelPricingPolicy.ResolvedPrice price = HotelPricingPolicy.resolve(
                LocalDate.of(2026, 8, 3), 38800,
                List.of(new HotelPricingPolicy.PriceCandidate(3, "周末价", null, null,
                        Set.of(6, 7), 42800, 10)));

        assertEquals(38800, price.priceCent());
        assertNull(price.ruleId());
        assertEquals("BASE", price.sourceType());
    }
}
