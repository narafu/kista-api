package com.kista.admin.application.port.output;

import com.kista.contract.trading.ReorderBuyBudgetResponse;
import com.kista.contract.trading.ReorderRequest;
import com.kista.contract.trading.ReorderResponse;
import com.kista.contract.trading.ReorderTimingAvailabilityResponse;
import com.kista.contract.trading.TradeCorrectionRequest;
import com.kista.contract.trading.TradeCorrectionResponse;
import com.kista.sharedkernel.StrategyStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TradingCommandPort {
    ReorderResponse reorder(ReorderRequest command);
    TradeCorrectionResponse correctManualFills(TradeCorrectionRequest command);
    ReorderTimingAvailabilityResponse reorderTimingAvailability();
    // BUY 재주문 예산 일괄 조회 — 같은 계좌 원본들, tradeDate null이면 첫 원본 주문 거래일
    ReorderBuyBudgetResponse reorderBuyBudget(List<UUID> orderIds, LocalDate tradeDate);
    // 전략 일시정지/재개 — 내부에서 소유권 검증(strategy.accountId == accountId) 후 저장, 불일치 시 404
    void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status);
}
