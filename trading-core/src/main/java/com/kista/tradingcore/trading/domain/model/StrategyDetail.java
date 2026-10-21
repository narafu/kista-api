package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.Market;

import java.math.BigDecimal;
import java.time.LocalDate;

// Strategy + 현재 StrategyCycle 상태 — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(
        Strategy strategy,
        BigDecimal initialUsdDeposit,
        LocalDate startDate,    // 사이클 시작일(예정) — 미래면 아직 매매 시작 전
        Integer divisionCount,
        boolean isReverseMode,
        Double currentRound,    // INFINITE 전략만 non-null, 이력 없으면 null
        Integer currentHoldings, // 최신 cycle_position 기준 보유 수량 (코인 DCA는 null)
        VrSummary vr,           // VR 전략만 non-null, 비VR은 null (com.kista.tradingcore.trading.domain.model.VrSummary)
        DcaSummary dca          // DCA 전략만 non-null
) {
    public StrategyDetail {
        // 코인 DCA 원장은 소수 보유라 정수 필드에 싣지 않는다 — 소수 보유·평단은 dca.currentHoldings/avgPrice, 실잔고는 crypto-portfolio
        if (strategy.ticker().getMarket() == Market.CRYPTO_KRW) {
            currentHoldings = null;
        }
    }
}
