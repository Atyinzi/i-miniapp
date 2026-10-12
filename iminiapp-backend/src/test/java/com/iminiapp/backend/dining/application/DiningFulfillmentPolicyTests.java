package com.iminiapp.backend.dining.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiningFulfillmentPolicyTests {

    @Test
    void onlyDineInAndInternalDeliveryCanBeCreated() {
        assertTrue(DiningFulfillmentPolicy.canCreate("DINE_IN"));
        assertTrue(DiningFulfillmentPolicy.canCreate(" delivery "));
        assertFalse(DiningFulfillmentPolicy.canCreate("PICKUP"));
        assertFalse(DiningFulfillmentPolicy.canCreate(null));
    }

    @Test
    void onlyDineInCanUseOnsiteVerification() {
        assertTrue(DiningFulfillmentPolicy.canVerifyOnSite("DINE_IN"));
        assertFalse(DiningFulfillmentPolicy.canVerifyOnSite("DELIVERY"));
        assertFalse(DiningFulfillmentPolicy.canVerifyOnSite("PICKUP"));
    }
}
