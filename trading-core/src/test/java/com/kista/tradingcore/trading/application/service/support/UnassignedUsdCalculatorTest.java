package com.kista.tradingcore.trading.application.service.support;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnassignedUsdCalculatorTest {

    StrategyPort strategyPort = mock(StrategyPort.class);
    CyclePositionPort cyclePositionPort = mock(CyclePositionPort.class);
    MarginPort marginPort = mock(MarginPort.class);
    UnassignedUsdCalculator calc = new UnassignedUsdCalculator(strategyPort, cyclePositionPort, marginPort,
            new CycleOrderStrategies(List.of(new InfiniteCycleOrderStrategy(null, null), new DcaCycleOrderStrategy())));

    private static Strategy strategy(UUID accountId, StrategyType type) {
        return new Strategy(UUID.randomUUID(), accountId, type, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    private void deposit(Strategy s, String usd) {
        when(cyclePositionPort.findLatestOneByStrategyId(s.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(usd), null, null, BigDecimal.ZERO, null, null)));
    }

    @Test
    void subtracts_only_seed_occupying_strategies() {
        UUID accountId = UUID.randomUUID();
        Strategy inf = strategy(accountId, StrategyType.INFINITE);
        Strategy dca = strategy(accountId, StrategyType.DCA);
        deposit(inf, "1200");
        deposit(dca, "999"); // DCA 원장은 자기 몫이 아니므로 제외
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(inf, dca));
        when(marginPort.getUsdBuyableAmount(any())).thenReturn(new BigDecimal("5000"));

        var ref = TradingFixtures.tradingAccount(accountId, UUID.randomUUID()).brokerRef();
        assertThat(calc.available(accountId, ref)).isEqualByComparingTo("3800");
    }

    @Test
    void applies_only_to_real_us_stock_brokers() {
        assertThat(UnassignedUsdCalculator.applies(Broker.KIS)).isTrue();
        assertThat(UnassignedUsdCalculator.applies(Broker.MOCK)).isFalse();
        assertThat(UnassignedUsdCalculator.applies(Broker.UPBIT)).isFalse();
    }
}
