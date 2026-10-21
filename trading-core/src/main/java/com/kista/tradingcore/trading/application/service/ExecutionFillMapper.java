package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.shared.kernel.OrderDirection;

import java.math.BigDecimal;

// broker 체결(Execution) → 잔고 재계산용 AccountBalance.Fill 변환 — matching이 broker를 참조하지 않도록
// 호출부(TradingReporter/ManualTradeCorrectionService/CryptoLedgerRecorder)에서 공용으로 변환
final class ExecutionFillMapper {

    private ExecutionFillMapper() {
    }

    static AccountBalance.Fill toFill(Execution execution) {
        return of(execution.direction(), BigDecimal.valueOf(execution.quantity()), execution.amountUsd());
    }

    // 값으로 직접 만드는 체결 — 코인 체결 동기화(CryptoLedgerRecorder)처럼 broker Execution이 없는 경로용
    static AccountBalance.Fill of(OrderDirection direction, BigDecimal quantity, BigDecimal amount) {
        return new ExecutionFill(direction, quantity, amount);
    }

    private record ExecutionFill(OrderDirection direction, BigDecimal quantity, BigDecimal amountUsd)
            implements AccountBalance.Fill {
    }
}
