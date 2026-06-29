package com.kista.domain.model.strategy;

import java.util.UUID;

public record CyclePositionInfiniteDetail(
        UUID cyclePositionId,
        boolean isReverseMode
) {}
