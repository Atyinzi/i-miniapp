package com.iminiapp.backend.dining.application;

import java.util.Locale;
import java.util.Set;

/** Central policy for dining fulfilment modes exposed by current APIs. */
public final class DiningFulfillmentPolicy {
    private static final Set<String> CREATABLE_TYPES = Set.of("DINE_IN", "DELIVERY");

    private DiningFulfillmentPolicy() {
    }

    public static boolean canCreate(String value) {
        return CREATABLE_TYPES.contains(normalize(value));
    }

    public static boolean canVerifyOnSite(String value) {
        return "DINE_IN".equals(normalize(value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
