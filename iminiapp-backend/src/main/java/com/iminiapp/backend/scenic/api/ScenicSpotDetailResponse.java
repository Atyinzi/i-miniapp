package com.iminiapp.backend.scenic.api;

import java.math.BigDecimal;

public record ScenicSpotDetailResponse(
        long id,
        long siteId,
        String siteCode,
        String name,
        String categoryCode,
        String description,
        String coverUrl,
        String address,
        BigDecimal longitude,
        BigDecimal latitude,
        String servicePhone,
        String introduction,
        String attributes) {
}
