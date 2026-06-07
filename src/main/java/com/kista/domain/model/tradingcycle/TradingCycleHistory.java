package com.kista.domain.model.tradingcycle;

import com.kista.domain.model.strategy.AccountBalance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// TradingService.execute() 종료 시 1건 적재
public record TradingCycleHistory(
        UUID id,                 // PK (null이면 @GeneratedValue)
        UUID tradingCycleId,     // FK → trading_cycle.id (UUID 간접 참조)
        BigDecimal usdDeposit,   // 통합주문가능금액 (매매 공식 B 계산 기준)
        BigDecimal closingPrice, // 종가 (PRIVACY 또는 초기 등록 시 null)
        BigDecimal avgPrice,     // 평균 매입 단가 (보유수량 0이면 null)
        int holdings,            // 보유 수량
        Instant createdAt        // 생성 시각 (null이면 DB DEFAULT)
) {
    // 사이클 등록·재등록 시 시작점 스냅샷 (holdings=0, avgPrice=null)
    // price: 재등록이면 현재가, 최초 등록이면 null
    public static TradingCycleHistory startSnapshot(UUID cycleId, BigDecimal usdDeposit, BigDecimal price) {
        return new TradingCycleHistory(null, cycleId, usdDeposit, price, null, 0, null);
    }

    // 매매 완료 후 실잔고 스냅샷
    public static TradingCycleHistory tradeSnapshot(UUID cycleId, AccountBalance balance, BigDecimal closingPrice) {
        return new TradingCycleHistory(null, cycleId,
                balance.usdDeposit(), closingPrice, balance.avgPrice(), balance.holdings(), null);
    }
}
