package com.iminiapp.backend.identity.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPasswordPolicyTests {
    @Test
    void rejectsLengthOnlyAndRepeatedPasswords() {
        assertFalse(AdminPasswordPolicy.isStrong("111111111111"));
        assertFalse(AdminPasswordPolicy.isStrong("abcdefghijkl"));
        assertFalse(AdminPasswordPolicy.isStrong("123456789012"));
        assertFalse(AdminPasswordPolicy.isStrong("aaaaaaaaaaa1"));
    }

    @Test
    void acceptsLongMixedNonRepeatingPassword() {
        assertTrue(AdminPasswordPolicy.isStrong("NorthCity-2026-Safe"));
    }
}
