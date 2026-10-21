package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.shared.kernel.OrderDirection;

import java.math.BigDecimal;

// broker 체결(Execution) → 잔고 재계산용 AccountBalance.Fill 변환 — matching이 broker를 참조하지 않도록
// 호출부(TradingReporter/ManualTradeCorrectionService)에서 공용으로 변환
final class ExecutionFillMapper {

    private ExecutionFillMapper() {
    }

    static AccountBalance.Fill toFill(Execution execution) {
        return new ExecutionFill(execution.direction(), BigDecimal.valueOf(execution.quantity()), execution.amountUsd());
    }

    private record ExecutionFill(OrderDirection direction, BigDecimal quantity, BigDecimal amountUsd)
            implements AccountBalance.Fill {
    }
}
