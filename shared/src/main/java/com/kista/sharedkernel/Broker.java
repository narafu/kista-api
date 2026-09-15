package com.kista.sharedkernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 증권사 식별자 — Account 도메인 nested enum(Broker)에서 sharedkernel로 이관(constraints.md "nested enum 정책").
// 상수명 byte-identical 유지 필수 — accounts.broker @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum Broker {
    TOSS("토스증권",    "토스"),  // 토스증권 Open API
    KIS("한국투자증권", "한투"),  // 한국투자증권 Open API
    MOCK("모의계좌",    "모의");  // 증권사 연동 없는 DB 기반 모의매매

    private final String label;      // 한국어 전체 이름
    private final String shortLabel; // UI 모바일 약칭
}
