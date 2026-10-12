package com.iminiapp.backend.hotel.application;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class HotelPricingPolicy {
    private HotelPricingPolicy() {}

    public static ResolvedPrice resolve(LocalDate date, long basePriceCent, List<PriceCandidate> candidates) {
        return candidates.stream()
                .filter(candidate -> candidate.matches(date))
                .max(Comparator.comparingInt(PriceCandidate::priority).thenComparingLong(PriceCandidate::id))
                .map(candidate -> new ResolvedPrice(candidate.priceCent(), candidate.id(), candidate.name(), "RULE"))
                .orElseGet(() -> new ResolvedPrice(basePriceCent, null, null, "BASE"));
    }

    public record PriceCandidate(long id, String name, LocalDate startDate, LocalDate endDate,
                                 Set<Integer> daysOfWeek, long priceCent, int priority) {
        boolean matches(LocalDate date) {
            if (startDate != null && date.isBefore(startDate)) return false;
            if (endDate != null && date.isAfter(endDate)) return false;
            return daysOfWeek == null || daysOfWeek.isEmpty() || daysOfWeek.contains(date.getDayOfWeek().getValue());
        }
    }

    public record ResolvedPrice(long priceCent, Long ruleId, String ruleName, String sourceType) {}
}
