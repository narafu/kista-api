package com.kista.shared.kernel;

import java.time.Instant;
import java.time.LocalDate;

// KST 일자 구간 ↔ Instant 경계 변환 단일 소스 — plusDays(1).atStartOfDay(...) 인라인 금지
public final class KstDays {

    // day 당일을 포함하는 반개구간 상한 — 다음 날 KST 00:00 (exclusive)
    public static Instant endExclusive(LocalDate day) {
        return day.plusDays(1).atStartOfDay(TimeZones.KST).toInstant();
    }

    private KstDays() {}
}
