package com.iminiapp.backend.scenic.api;

import java.util.List;

public record PackageComponentUpdateRequest(List<PackageComponentRequest> components) {
}
