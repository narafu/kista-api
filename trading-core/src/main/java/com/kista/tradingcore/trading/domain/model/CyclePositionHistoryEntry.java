package com.kista.tradingcore.trading.domain.model;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.Quantities;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

// cycle_position + strategy_cycle + strategy join 조회 결과 (계좌·사이클 기준 이력 조회 전용)
public record CyclePositionHistoryEntry(
        UUID id,
        UUID accountId,           // strategy.accountId (전략 없으면 null)
        StrategyTicker ticker,    // strategy.ticker
        StrategyType strategyType, // strategy.type (전략 없으면 null)
        BigDecimal deposit,     // 통합주문가능금액
        BigDecimal closingPrice,   // 종가 (null 가능)
        BigDecimal avgPrice,       // 평균 매입 단가 (holdings=0이면 null)
        BigDecimal holdings,             // 보유 수량
        Instant createdAt          // 기록 시각
) {
    public CyclePositionHistoryEntry {
        holdings = Quantities.normalize(holdings); // numeric(20,8) 꼬리 0 제거
    }

    // 응답용 — 예수금만 계좌 통화 자릿수로 반올림(가격·수량은 그대로)
    public CyclePositionHistoryEntry roundedTo(Currency currency) {
        return new CyclePositionHistoryEntry(id, accountId, ticker, strategyType,
                currency.round(deposit), closingPrice, avgPrice, holdings, createdAt);
    }

    // 평가금액 = closingPrice × holdings (closingPrice 없으면 0)
    public BigDecimal marketValue() {
        return closingPrice != null
                ? closingPrice.multiply(holdings).setScale(AccountBalance.LEDGER_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    // 총 자산 = 평가금액 + 예수금
    public BigDecimal totalAsset() {
        return marketValue().add(deposit).setScale(AccountBalance.LEDGER_SCALE, RoundingMode.HALF_UP);
    }
}
