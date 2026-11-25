package com.kista.tradingcore.trading.application.service.support;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnassignedCashCalculatorTest {

    StrategyPort strategyPort = mock(StrategyPort.class);
    CyclePositionPort cyclePositionPort = mock(CyclePositionPort.class);
    MarginPort marginPort = mock(MarginPort.class);
    UnassignedCashCalculator calc = new UnassignedCashCalculator(strategyPort, cyclePositionPort, marginPort);

    private static Strategy strategy(UUID accountId, StrategyType type) {
        return new Strategy(UUID.randomUUID(), accountId, type, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    private void deposit(Strategy s, String usd) {
        when(cyclePositionPort.findLatestOneByStrategyId(s.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(usd), null, null, BigDecimal.ZERO, null, null)));
    }

    @Test
    void subtracts_every_seed_occupying_strategy_including_dca() {
        UUID accountId = UUID.randomUUID();
        Strategy inf = strategy(accountId, StrategyType.INFINITE);
        Strategy dca = strategy(accountId, StrategyType.DCA);
        deposit(inf, "1200");
        deposit(dca, "999"); // DCA도 원장 예수금이 자기 몫이라 함께 뺀다
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(inf, dca));
        when(marginPort.getUsdBuyableAmount(any())).thenReturn(new BigDecimal("5000"));

        var ref = TradingFixtures.tradingAccount(accountId, UUID.randomUUID()).brokerRef();
        assertThat(calc.available(accountId, ref, null)).isEqualByComparingTo("2801"); // 5000 − 1200 − 999
    }

    @Test
    void available_ignores_archived_deposit() {
        UUID accountId = UUID.randomUUID();
        Strategy inf = strategy(accountId, StrategyType.INFINITE);
        Strategy archived = new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE, StrategyStatus.ARCHIVED,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        deposit(inf, "1200");
        deposit(archived, "999"); // 보관 전략의 잔여 예수금은 시드를 점유하지 않는다
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(inf, archived));
        when(marginPort.getUsdBuyableAmount(any())).thenReturn(new BigDecimal("5000"));

        var ref = TradingFixtures.tradingAccount(accountId, UUID.randomUUID()).brokerRef();
        assertThat(calc.available(accountId, ref, null)).isEqualByComparingTo("3800"); // 5000 − 1200
    }

    @Test
    void available_keeps_excluded_strategy_deposit() {
        UUID accountId = UUID.randomUUID();
        Strategy self = strategy(accountId, StrategyType.PRIVACY);
        Strategy other = strategy(accountId, StrategyType.INFINITE);
        deposit(self, "1200");
        deposit(other, "999");
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(self, other));
        when(marginPort.getUsdBuyableAmount(any())).thenReturn(new BigDecimal("5000"));

        var ref = TradingFixtures.tradingAccount(accountId, UUID.randomUUID()).brokerRef();
        // 시드 수정 대상 자신의 원장은 그 시드로 다시 쓸 수 있어 빼지 않는다
        assertThat(calc.available(accountId, ref, self.id())).isEqualByComparingTo("4001"); // 5000 − 999
    }

    @Test
    void seedHolders_excludes_archived_and_zero_fills_missing_ledger() {
        UUID accountId = UUID.randomUUID();
        Strategy active = strategy(accountId, StrategyType.INFINITE);
        Strategy paused = new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE, StrategyStatus.PAUSED,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        Strategy archived = new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE, StrategyStatus.ARCHIVED,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        when(cyclePositionPort.findLatestOneByStrategyId(active.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), null, null, new BigDecimal("2"), null, null)));
        when(cyclePositionPort.findLatestOneByStrategyId(paused.id())).thenReturn(Optional.empty()); // 원장 없음
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(active, paused, archived));

        var holders = calc.seedHolders(accountId);
        assertThat(holders).hasSize(2);
        assertThat(UnassignedCashCalculator.reserved(holders)).isEqualByComparingTo("100");
        var noLedger = holders.get(1);
        assertThat(noLedger.deposit()).isEqualByComparingTo("0");
        assertThat(noLedger.holdings()).isEqualByComparingTo("0");
        assertThat(holders.get(0).holdings()).isEqualByComparingTo("2");
    }

    @Test
    void applies_to_every_broker_except_mock() {
        assertThat(UnassignedCashCalculator.applies(Broker.TOSS)).isTrue();
        assertThat(UnassignedCashCalculator.applies(Broker.MOCK)).isFalse();
    }
}
