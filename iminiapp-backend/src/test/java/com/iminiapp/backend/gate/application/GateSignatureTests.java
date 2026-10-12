package com.iminiapp.backend.gate.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GateSignatureTests {
    @Test
    void shouldUseDocumentedHmacPayloadOrder() {
        String signature = GateSignature.calculate(
                "0123456789abcdef0123456789abcdef",
                "GATE-01",
                1786464000L,
                "0123456789abcdef",
                "EVT-20260812-0001",
                "BFHV-1-1-ABCDEF123456");

        assertThat(signature).isEqualTo("bafd75a87b406b68c38727b13c3e75415fae44d0c2c960c52d00eae8e992ef03");
    }

    @Test
    void eventStorageHashMustNotContainRawCode() {
        String raw = "BFHV-1-1-ABCDEF123456";
        String hash = GateAccessService.sha256(raw);

        assertThat(hash).hasSize(64).doesNotContain(raw);
    }
}
