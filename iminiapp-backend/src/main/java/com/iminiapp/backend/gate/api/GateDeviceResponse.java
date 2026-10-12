package com.iminiapp.backend.gate.api;

import java.time.LocalDateTime;

public record GateDeviceResponse(
        long id,
        String deviceNo,
        String name,
        String venueCode,
        String integrationMode,
        String status,
        LocalDateTime lastSeenAt,
        LocalDateTime updatedAt,
        String provisioningSecret) { }
