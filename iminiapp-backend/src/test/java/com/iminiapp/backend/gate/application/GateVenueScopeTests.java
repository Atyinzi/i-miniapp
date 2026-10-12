package com.iminiapp.backend.gate.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateVenueScopeTests {
    @Test
    void acceptsOnlyTheTicketVenue() {
        assertTrue(GateConsumeTransactionService.venueMatches(
                List.of("WEAPON_CITY"), "weapon_city"));
        assertFalse(GateConsumeTransactionService.venueMatches(
                List.of("WEAPON_CITY"), "NORTH_HOTEL"));
    }

    @Test
    void failsClosedForMissingOrBlankVenueScope() {
        assertFalse(GateConsumeTransactionService.venueMatches(List.of(), "WEAPON_CITY"));
        assertFalse(GateConsumeTransactionService.venueMatches(List.of("WEAPON_CITY"), null));
        assertFalse(GateConsumeTransactionService.venueMatches(
                java.util.Arrays.asList(null, ""), "WEAPON_CITY"));
    }
}
