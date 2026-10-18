package com.kista.tradingcore.broker.domain.model;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.Map;

// 코인 거래소 잔고 — 원화 가용·잠금 + 지원 코인별 보유(소수 8자리)·평단(KRW). 미지원 코인은 담지 않는다
public record CryptoBalance(
        BigDecimal krwAvailable,                  // 주문 가능 원화
        BigDecimal krwLocked,                     // 주문·출금 대기로 잠긴 원화
        Map<StrategyTicker, Holding> holdings     // 지원 코인별 보유
) {
    public CryptoBalance {
        holdings = Map.copyOf(holdings);
    }

    public record Holding(
            BigDecimal available,   // 매도 가능 수량
            BigDecimal locked,      // 주문 대기로 잠긴 수량
            BigDecimal avgBuyPrice  // 평균 매수가 (KRW, 업비트 avg_buy_price)
    ) {
        // 보유 총수량 — 평가·표시용
        public BigDecimal total() {
            return available.add(locked);
        }
    }
}
