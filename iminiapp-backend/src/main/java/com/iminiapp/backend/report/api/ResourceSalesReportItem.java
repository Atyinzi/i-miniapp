package com.iminiapp.backend.report.api;

public record ResourceSalesReportItem(
        long resourceId,
        String resourceName,
        String resourceType,
        long orderCount,
        long quantity,
        long grossSalesCent) {
}
