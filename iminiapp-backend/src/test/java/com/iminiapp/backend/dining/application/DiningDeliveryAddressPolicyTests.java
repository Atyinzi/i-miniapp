package com.iminiapp.backend.dining.application;

import com.iminiapp.backend.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiningDeliveryAddressPolicyTests {
    private static final List<String> AREAS = List.of("示例宾馆客房区", "示例宾馆会议区");

    @Test
    void acceptsConfiguredInternalAreaAndRoom() {
        assertEquals("示例宾馆客房区 · 302房",
                DiningDeliveryAddressPolicy.validate("示例宾馆客房区", "302房", null, AREAS, true));
    }

    @Test
    void rejectsExternalOrUnconfiguredAddress() {
        assertThrows(BusinessException.class,
                () -> DiningDeliveryAddressPolicy.validate("市中心火车站", "入口", null, AREAS, true));
    }

    @Test
    void rejectsMissingRoomWhenRequiredAndValidatesLegacyPayload() {
        assertThrows(BusinessException.class,
                () -> DiningDeliveryAddressPolicy.validate("示例宾馆会议区", null, null, AREAS, true));
        assertEquals("示例宾馆会议区 · 3层会议室",
                DiningDeliveryAddressPolicy.validate(null, null, "示例宾馆会议区 · 3层会议室", AREAS, true));
    }
}
