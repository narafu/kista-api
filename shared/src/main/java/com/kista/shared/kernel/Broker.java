package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 증권사 식별자 — Account 도메인 nested enum(Broker)에서 shared.kernel로 이관(constraints.md "User/Account/Strategy 공유 enum").
// 상수명 byte-identical 유지 필수 — accounts.broker @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum Broker {
    TOSS("토스증권",    "토스", Market.US_STOCK, Currency.USD),  // 토스증권 Open API
    KIS("한국투자증권", "한투", Market.US_STOCK, Currency.USD),  // 한국투자증권 Open API
    MOCK("모의계좌",    "모의", Market.US_STOCK, Currency.USD),  // 증권사 연동 없는 DB 기반 모의매매
    UPBIT("업비트",     "업비트", Market.CRYPTO_KRW, Currency.KRW); // 업비트 Open API (KRW 마켓 코인)

    private final String label;      // 한국어 전체 이름
    private final String shortLabel; // UI 모바일 약칭
    private final Market market;     // 거래 시장 — 종목 시장과 일치해야 전략 생성 가능
    private final Currency currency; // 계좌 기준 통화 (주문 금액 단위)

    // 개장 경매 주문(MOO/LOO)을 개장 전에 접수할 수 있는 증권사 — KIS만 31/32 지원, Toss·MOCK은 개장 후 지정가 근사
    public boolean supportsOpenAuctionOrders() {
        return this == KIS;
    }
}
