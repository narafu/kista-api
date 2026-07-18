package com.kista.domain.model.privacy;

import java.time.LocalDate;

// 기준 매매표 발행일(release_date, KST 원본) ↔ 적용 거래일(KST) 업무 규칙.
// 기준표는 발행 다음날 KST 거래일 세션에 적용된다 — 시간대 변환이 아니라 도메인 규칙이므로
// UsTradeDates(구 TradeDateConverter)와 혼용 금지.
public final class PrivacyDates {

    // 거래일 → 그 세션에 적용되는 기준표 발행일 (전날)
    public static LocalDate releaseDateFor(LocalDate kstTradeDate) {
        return kstTradeDate.minusDays(1);
    }

    // 발행일 → 적용 거래일 (다음날)
    public static LocalDate tradeDateOf(LocalDate releaseDate) {
        return releaseDate.plusDays(1);
    }

    private PrivacyDates() {}
}
