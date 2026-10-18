package com.kista.tradingcore.stats.domain.model;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

// 코인 계좌 포트폴리오 — 원화 예수금 + 코인별 보유·평단·현재가·평가액(KRW)
public record CryptoPortfolio(
        BigDecimal krwCash,     // 원화 예수금 (가용 + 주문 대기 잠금)
        List<Holding> holdings  // 지원 코인별 보유
) {
    public CryptoPortfolio {
        holdings = List.copyOf(holdings);
    }

    public record Holding(
            StrategyTicker ticker,     // 코인 종목
            BigDecimal quantity,       // 보유 총수량 (가용 + 잠금)
            BigDecimal avgBuyPrice,    // 평균 매수가 (KRW)
            BigDecimal currentPrice,   // 현재가 (KRW, 시세 없으면 null)
            BigDecimal evalAmountKrw   // 평가액 = 수량 × 현재가 (원 단위 반올림, 시세 없으면 null)
    ) {}

    // 총자산 = 예수금 + 평가액 합 (시세 없는 코인은 평가액 null이라 제외)
    public BigDecimal totalAssetKrw() {
        return holdings.stream().map(Holding::evalAmountKrw).filter(Objects::nonNull).reduce(krwCash, BigDecimal::add);
    }
}
