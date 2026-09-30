package com.kista.web.dto;

import java.util.List;

// StrategyCapabilityResponse(trading-core) own-type — matching 타입을 root로 노출하지 않기 위함 (4단계에서 엔드포인트째 삭제 예정이라 contract로 옮기지 않음)
public record StrategyCapability(
        boolean requiresPrivacyBase,
        boolean supportsReverseMode,
        List<Integer> divisionCounts
) {
}
