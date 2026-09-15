package com.kista.web.dto;

import java.util.List;

// StrategyCapabilityResponse(trading-core) own-type — matching 타입을 root로 노출하지 않기 위함
public record StrategyCapability(
        boolean requiresPrivacyBase,
        boolean supportsReverseMode,
        List<Integer> divisionCounts
) {
}
