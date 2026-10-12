package com.iminiapp.backend.gate.api;

import java.time.LocalDateTime;

public record GateEventResponse(
        long id,
        long deviceId,
        String deviceNo,
        String deviceName,
        String eventNo,
        String decision,
        String message,
        Long verificationId,
        Long orderId,
        LocalDateTime happenedAt,
        LocalDateTime createdAt) { }
