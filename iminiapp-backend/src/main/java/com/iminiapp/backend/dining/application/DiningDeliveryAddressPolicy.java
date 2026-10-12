package com.iminiapp.backend.dining.application;

import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.util.StringUtils;

import java.util.List;

final class DiningDeliveryAddressPolicy {
    private static final String SEPARATOR = " · ";
    private DiningDeliveryAddressPolicy() { }

    static String validate(String deliveryArea, String roomNo, String legacyAddress,
                           List<String> allowedAreas, boolean roomRequired) {
        String area = clean(deliveryArea, 120, "内部配送区域");
        String room = clean(roomNo, 80, "房号或会议室号");
        if (area == null && StringUtils.hasText(legacyAddress)) {
            String[] parts = legacyAddress.trim().split("\\s*·\\s*", 2);
            area = clean(parts[0], 120, "内部配送区域");
            room = parts.length == 2 ? clean(parts[1], 80, "房号或会议室号") : null;
        }
        if (area == null || allowedAreas == null || !allowedAreas.contains(area)) {
            throw invalid("配送地址必须选择该门店已配置的宾馆内部区域");
        }
        if (roomRequired && room == null) throw invalid("请填写房号或会议室号");
        return room == null ? area : area + SEPARATOR + room;
    }

    private static String clean(String value, int max, String label) {
        if (!StringUtils.hasText(value)) return null;
        String result = value.trim();
        if (result.length() > max || result.contains("\n") || result.contains("\r")) {
            throw invalid(label + "格式不正确");
        }
        return result;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
    }
}
