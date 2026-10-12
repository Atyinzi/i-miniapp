package com.iminiapp.backend.location.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LocationPlaceResponse(long id, String placeCode, String placeType, String name, String address,
                                    BigDecimal longitude, BigDecimal latitude, String coordinateSystem,
                                    int sortOrder, String status, boolean configured, LocalDateTime updatedAt) {
}
