package com.kista.tradingcore.trading.application.service.support;

import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;

import java.time.LocalDate;
import com.kista.shared.kernel.StrategyStatus;

// 보정 서비스 공용 — holdings 소진(==0) 시 사이클 종료 + 전략 PAUSED 처리
public final class CycleCloser {

    private CycleCloser() {}

    // holdings==0이면 사이클 종료 후 갱신된 전략 상태 반환, 아니면 원본 그대로
    public static CycleEndResult closeIfExhausted(StrategyCyclePort strategyCyclePort, StrategyPort strategyPort,
                                           Strategy strategy, StrategyCycle currentCycle,
                                           AccountBalance balance, LocalDate tradeDate) {
        if (!balance.hasHoldings()) {
            strategyCyclePort.markEnded(currentCycle.id(), balance.usdDeposit(), tradeDate);
            // 조건부 갱신 — 오래된 스냅샷 save가 동시에 보관된 ARCHIVED를 PAUSED로 되돌리지 않게 한다
            strategyPort.pause(strategy.id());
            return new CycleEndResult(strategy.withStatus(StrategyStatus.PAUSED), true, tradeDate);
        }
        return new CycleEndResult(strategy, false, null);
    }

    // 사이클 종료 여부와 갱신된 전략 상태를 함께 반환하는 값 객체
    public record CycleEndResult(Strategy strategy, boolean ended, LocalDate endDate) {}
}
