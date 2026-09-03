package com.kista.sharedkernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 전략 실행 상태 — Strategy 도메인 nested enum(Status)에서 sharedkernel로 이관(constraints.md "nested enum 정책 개정").
// 상수명 byte-identical 유지 필수 — StrategyEntity.status @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum StrategyStatus {
    ACTIVE("운영중"),  // 매매 스케쥴링 실행 중
    PAUSED("일시중지"); // 매매 중지 (스케쥴링 제외)

    private final String label; // 한국어 표시 이름
}
