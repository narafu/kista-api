package com.kista.platform.time;

import java.time.LocalDate;

// KST 거래일 ↔ US 거래일 변환 — US 기준 외부 데이터(KIS API, 휴장일 캘린더)를 만나는 어댑터 내부 전용.
// KST 거래일(매매 정산 아침)은 항상 US 거래일 다음날이므로 단순 ±1일이 성립한다.
// 주말/휴일 보정 없음 — 실제 KST 거래일만 입력된다는 전제. 비거래일이 들어오면 결과가 US 비거래일이 될 수 있고,
// 호출부는 응답 봉 날짜 불일치 시 현재가 폴백으로 이를 흡수한다(KisPriceApi.fetchConfirmedClose 등).
// 도메인·서비스·persistence(orders)에서는 사용 금지 — 전 구간 KST 단일 기준.
// 사용처는 HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters가
// 클래스 단위 allowlist로 강제한다.
public final class UsTradeDates {

    // KST 거래일 → US 거래일. 예: KST 5/27 → US 5/26
    public static LocalDate toUsTradeDate(LocalDate kstTradeDate) {
        return kstTradeDate.minusDays(1);
    }

    // US 거래일 → KST 거래일. 예: US 5/26 → KST 5/27
    public static LocalDate toKstTradeDate(LocalDate usTradeDate) {
        return usTradeDate.plusDays(1);
    }

    private UsTradeDates() {}
}
