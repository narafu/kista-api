package com.kista.matching.adapter.in.web;

import com.kista.matching.domain.strategy.CycleOrderStrategy;

import java.util.List;

// MetaController(root)가 소비하는 내부 전용 응답 — matching 타입을 root에 노출하지 않기 위한 투영
public record StrategyCapabilityResponse(
        boolean requiresPrivacyBase,
        boolean supportsReverseMode,
        List<Integer> divisionCounts
) {
    public static StrategyCapabilityResponse from(CycleOrderStrategy strategy) {
        return new StrategyCapabilityResponse(
                strategy.requiresPrivacyBase(), strategy.supportsReverseMode(), strategy.availableDivisionCounts());
    }
}
