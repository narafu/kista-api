package com.kista.trading.domain.model;

import java.util.UUID;

public record CyclePositionInfiniteDetail(
        UUID cyclePositionId,
        boolean isReverseMode
) {}
