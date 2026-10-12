package com.iminiapp.backend.location.api;

import java.math.BigDecimal;

public record LocationPlaceRequest(String placeCode, String placeType, String name, String address,
                                   BigDecimal longitude, BigDecimal latitude, String coordinateSystem,
                                   Integer sortOrder, String status) {
}
