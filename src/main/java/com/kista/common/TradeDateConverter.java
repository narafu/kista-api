package com.kista.common;

import java.time.LocalDate;

/**
 * tradeDate KST ↔ UTC(=US 거래일) 변환 헬퍼.
 *
 * 의미: tradeDate=2026-05-26(UTC=ET 거래일) = KST 2026-05-27 새벽 04:30 매매 실행.
 * KST 04:30 은 항상 UTC 전날 19:30이므로 단순 ±1일 변환이 성립한다.
 *
 * 적용 위치: Persistence Adapter의 toEntity/toDomain 및 DB 조회 파라미터.
 * 인라인 .minusDays(1)/.plusDays(1) 사용 금지 — 반드시 이 헬퍼 경유.
 */
public final class TradeDateConverter {

    // 도메인(KST) → DB(UTC=US 거래일). 예: KST 5/27 → UTC 5/26
    public static LocalDate toUtc(LocalDate kstDate) {
        return kstDate.minusDays(1);
    }

    // DB(UTC=US 거래일) → 도메인(KST). 예: UTC 5/26 → KST 5/27
    public static LocalDate toKst(LocalDate utcDate) {
        return utcDate.plusDays(1);
    }

    private TradeDateConverter() {}
}
