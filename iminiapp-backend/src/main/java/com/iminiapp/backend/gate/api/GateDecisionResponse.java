package com.iminiapp.backend.gate.api;

import java.time.LocalDateTime;

public record GateDecisionResponse(
        String eventNo,
        boolean granted,
        String decision,
        String message,
        Long verificationId,
        Long orderId,
        LocalDateTime decidedAt) { }
