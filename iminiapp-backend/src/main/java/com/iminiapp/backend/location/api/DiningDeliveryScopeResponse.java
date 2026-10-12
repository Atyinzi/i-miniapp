package com.iminiapp.backend.location.api;

import java.util.List;

public record DiningDeliveryScopeResponse(long storeId, String storeCode, String storeName,
                                          String scopeType, List<String> areas, boolean roomRequired) {
}
