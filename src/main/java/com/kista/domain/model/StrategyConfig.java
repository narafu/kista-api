package com.kista.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record StrategyConfig(
        UUID id,
        String symbol,
        String strategy,
        boolean enabled,
        Map<String, Object> params,
        Instant createdAt,
        Instant updatedAt
) {}
