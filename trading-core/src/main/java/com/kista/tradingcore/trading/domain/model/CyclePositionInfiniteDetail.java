package com.kista.tradingcore.trading.domain.model;

import java.util.UUID;

public record CyclePositionInfiniteDetail(
        UUID cyclePositionId,
        boolean isReverseMode
) {}
