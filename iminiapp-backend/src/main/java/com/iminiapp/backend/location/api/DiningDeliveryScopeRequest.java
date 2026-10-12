package com.iminiapp.backend.location.api;

import java.util.List;

public record DiningDeliveryScopeRequest(List<String> areas, Boolean roomRequired) {
}
