package com.kista.application.service.admin;

import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;

import java.time.LocalDate;

// admin 보정 서비스 공용 — holdings 소진(==0) 시 사이클 종료 + 전략 PAUSED 처리
final class AdminCycleCloser {

    private AdminCycleCloser() {}

    // holdings==0이면 사이클 종료 후 갱신된 전략 상태 반환, 아니면 원본 그대로
    static CycleEndResult closeIfExhausted(StrategyCyclePort strategyCyclePort, StrategyPort strategyPort,
                                           Strategy strategy, StrategyCycle currentCycle,
                                           AccountBalance balance, LocalDate tradeDate) {
        if (balance.holdings() == 0) {
            strategyCyclePort.markEnded(currentCycle.id(), balance.usdDeposit(), tradeDate);
            Strategy updated = strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
            return new CycleEndResult(updated, true, tradeDate);
        }
        return new CycleEndResult(strategy, false, null);
    }

    // 사이클 종료 여부와 갱신된 전략 상태를 함께 반환하는 값 객체
    record CycleEndResult(Strategy strategy, boolean ended, LocalDate endDate) {}
}
