package com.iminiapp.backend.gate.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GateNonceCleanupJob {
    private final JdbcTemplate jdbc;

    public GateNonceCleanupJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${app.gate.nonce-cleanup-ms:3600000}")
    public void removeExpiredNonces() {
        jdbc.update("DELETE FROM bf_gate_request_nonce WHERE expires_at<CURRENT_TIMESTAMP");
    }
}
