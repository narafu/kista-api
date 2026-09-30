package com.kista.sharedkernel;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// 전략 타입별 정적 capability — 컴파일 타임 상수라 SSOT는 StrategyType.capability() 한 곳
// matching 커널(CycleOrderStrategy 기본 메서드)과 root /api/meta가 같은 값을 프로세스 간 HTTP 없이 공유한다
public record StrategyCapability(
        Set<StrategyTicker> availableTickers, // 사용 가능한 티커 집합
        boolean tickerFixed,                  // 티커 고정 여부 (단일 티커면 선택 불가)
        boolean requiresPrivacyBase,          // basePrice 소스가 기준 매매표인지 (PRIVACY만 true)
        boolean supportsReverseMode,          // 리버스모드(소진 후 모드) 지원 여부 (INFINITE만 true)
        List<Integer> divisionCounts          // 분할 수 옵션 — 빈 목록이면 분할 개념 없음
) {
    public StrategyCapability {
        // 방어적 복사 — 상수 공유 중 외부 변경 차단, EnumSet 순서(ordinal)를 유지해 UI 티커 정렬을 보존
        // EnumSet.copyOf는 빈 non-EnumSet 컬렉션에 IllegalArgumentException을 던지므로 빈 입력은 noneOf로 처리
        availableTickers = Collections.unmodifiableSet(availableTickers.isEmpty()
                ? EnumSet.noneOf(StrategyTicker.class)
                : EnumSet.copyOf(availableTickers));
        divisionCounts = List.copyOf(divisionCounts);
    }
}
