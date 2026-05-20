package com.kista.domain.model.admin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLog(
    UUID id,
    UUID adminId,
    String action,
    String targetType,
    UUID targetId,
    Map<String, Object> payload,
    Instant createdAt
) {}
