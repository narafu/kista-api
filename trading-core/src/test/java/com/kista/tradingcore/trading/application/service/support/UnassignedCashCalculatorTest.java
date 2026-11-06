package com.kista.tradingcore.trading.application.service.support;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
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
    CryptoExchanges cryptoExchanges = mock(CryptoExchanges.class);
    UnassignedCashCalculator calc = new UnassignedCashCalculator(strategyPort, cyclePositionPort, marginPort, cryptoExchanges);

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
        assertThat(calc.available(accountId, ref)).isEqualByComparingTo("2801"); // 5000 − 1200 − 999
    }

    @Test
    void crypto_account_uses_exchange_quote_available_minus_ledgers() {
        UUID accountId = UUID.randomUUID();
        Strategy dca = new Strategy(UUID.randomUUID(), accountId, StrategyType.DCA, StrategyStatus.ACTIVE, StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        deposit(dca, "300000");
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(dca));
        CryptoExchangePort upbit = mock(CryptoExchangePort.class);
        when(cryptoExchanges.of(Broker.UPBIT)).thenReturn(upbit);
        // 주문 가능 견적 통화 — 접수 주문 잠금분(quoteLocked)은 이미 빠져 있다
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(new BigDecimal("1000000"), new BigDecimal("50000"), Map.of()));

        var ref = TradingFixtures.upbitAccount(accountId, UUID.randomUUID()).toBrokerRef();
        assertThat(calc.available(accountId, ref)).isEqualByComparingTo("700000"); // 1,000,000 − 300,000
        verify(marginPort, never()).getUsdBuyableAmount(any());
    }

    @Test
    void applies_to_every_broker_except_mock() {
        assertThat(UnassignedCashCalculator.applies(Broker.KIS)).isTrue();
        assertThat(UnassignedCashCalculator.applies(Broker.UPBIT)).isTrue();
        assertThat(UnassignedCashCalculator.applies(Broker.BINANCE)).isTrue();
        assertThat(UnassignedCashCalculator.applies(Broker.MOCK)).isFalse();
    }
}
