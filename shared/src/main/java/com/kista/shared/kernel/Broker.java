package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 증권사 식별자 — Account 도메인 nested enum(Broker)에서 shared.kernel로 이관(constraints.md "User/Account/Strategy 공유 enum").
// 상수명 byte-identical 유지 필수 — accounts.broker @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum Broker {
    TOSS("토스증권",    "토스", Market.US_STOCK, false, false),  // 토스증권 Open API
    KIS("한국투자증권", "한투", Market.US_STOCK, false, false),  // 한국투자증권 Open API
    MOCK("모의계좌",    "모의", Market.US_STOCK, false, false),  // 증권사 연동 없는 DB 기반 모의매매
    UPBIT("업비트",     "업비트", Market.CRYPTO_KRW, true, true), // 업비트 Open API (KRW 마켓 코인)
    MERITZ("메리츠증권", "메리츠", Market.US_STOCK, true, false), // 메리츠증권 Open API (해외주식, 앱키에 계좌 종속)
    BINANCE("바이낸스", "바이낸스", Market.CRYPTO_USDT, true, false); // 바이낸스 현물 USDT 마켓 (API key/secret HMAC, 계좌번호 없음·키 만료일 없음)

    private final String label;      // 한국어 전체 이름
    private final String shortLabel; // UI 모바일 약칭
    private final Market market;     // 거래 시장 — 종목 시장과 일치해야 전략 생성 가능
    private final boolean keyOnlyCredentials; // 계좌번호 없이 앱키가 계좌를 식별(중복 판정 키=appKey) — 업비트·메리츠·바이낸스
    private final boolean apiKeyExpiring;      // API 키 만료가 있는 거래소 — 업비트

    // 계좌 기준 통화 (주문 금액 단위) — 시장의 견적 통화에서 파생
    public Currency getCurrency() {
        return market.quoteCurrency();
    }

    // 코인 거래소 여부
    public boolean isCryptoExchange() {
        return market.isCrypto();
    }

    // 개장 경매 주문(MOO/LOO)을 개장 전에 접수할 수 있는 증권사 — KIS만 31/32 지원, Toss·MOCK은 개장 후 지정가 근사
    public boolean supportsOpenAuctionOrders() {
        return this == KIS;
    }
}
