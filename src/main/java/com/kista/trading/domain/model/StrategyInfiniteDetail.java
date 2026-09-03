package com.kista.trading.domain.model;

import java.util.UUID;

public record StrategyInfiniteDetail(
        UUID strategyVersionId,
        int divisionCount
) {
    public StrategyInfiniteDetail {
        // 0/음수는 InfinitePosition의 분할 나눗셈에서 divide-by-zero·부호 반전을 일으킨다 —
        // 관리자 설정 저장 시점 검증(AdminSettingsRequest)만으로는 DB에 이미 저장된 값을 못 걸러내므로 여기서 원천 차단한다.
        if (divisionCount <= 0) {
            throw new IllegalArgumentException("divisionCount는 0보다 커야 합니다: " + divisionCount);
        }
    }
}
