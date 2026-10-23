package com.kista.tradingcore.trading.application.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.UUID;

// 코인 주문 체결 확인 — 체결 동기화가 markFilled 직후 발행
public record CryptoOrderFilledEvent(
        UUID userId,              // 알림 수신자
        UUID accountId,           // 대상 계좌
        UUID strategyId,          // 대상 전략
        String accountNickname,   // 알림 표기
        StrategyTicker ticker,    // 종목
        OrderDirection direction, // 매수/매도
        BigDecimal filledQuantity, // 체결 수량
        BigDecimal avgPrice,      // 체결 평균가 (계좌 통화)
        @JsonAlias("amountKrw") BigDecimal amount, // 체결 금액 = 수량 × 평균가, 계좌 통화 scale HALF_UP
        Currency currency         // 계좌 통화
) {
    public CryptoOrderFilledEvent {
        // EPR 구 row 호환 — currency 추가 전 발행분은 전부 업비트(KRW)
        if (currency == null) currency = Currency.KRW;
    }
}
