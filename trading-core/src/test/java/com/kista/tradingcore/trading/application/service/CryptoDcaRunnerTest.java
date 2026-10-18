package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CryptoDcaRunnerTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 13, 10);

    StrategyPort strategyPort = mock(StrategyPort.class);
    AccountPort accountPort = mock(AccountPort.class);
    StrategyCyclePort cyclePort = mock(StrategyCyclePort.class);
    TradingUserProfilePort profilePort = mock(TradingUserProfilePort.class);
    StrategyDcaDetailPort dcaDetailPort = mock(StrategyDcaDetailPort.class);
    CryptoExchanges exchanges = mock(CryptoExchanges.class);
    CryptoOrderSynchronizer synchronizer = mock(CryptoOrderSynchronizer.class);
    CryptoDcaRoundExecutor executor = mock(CryptoDcaRoundExecutor.class);
    TradingErrorReportPort errorReport = mock(TradingErrorReportPort.class);
    CryptoExchangePort exchange = mock(CryptoExchangePort.class);
    CryptoDcaRunner runner = new CryptoDcaRunner(strategyPort, accountPort, cyclePort, profilePort, dcaDetailPort,
            exchanges, synchronizer, executor, errorReport);

    List<Strategy> strategies = new java.util.ArrayList<>();
    List<Account> accounts = new java.util.ArrayList<>();
    Map<UUID, StrategyCycle> cycles = new HashMap<>();
    Map<UUID, DcaConfig> configs = new HashMap<>();

    // 계좌·전략·사이클·설정을 한 번에 등록
    Strategy add(Account account, StrategyType type, boolean withConfig) {
        if (!accounts.contains(account)) accounts.add(account);
        Strategy s = new Strategy(UUID.randomUUID(), account.id(), type, StrategyStatus.ACTIVE, StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        strategies.add(s);
        cycles.put(s.id(), new StrategyCycle(UUID.randomUUID(), s.id(), UUID.randomUUID(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 10, 1), null, null, null));
        if (withConfig) configs.put(s.id(), new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 13));
        return s;
    }

    void tick() {
        when(strategyPort.findAllActive()).thenReturn(strategies);
        when(accountPort.findAll()).thenReturn(accounts);
        when(profilePort.findAllByUserIds(any())).thenAnswer(i -> {
            Map<UUID, com.kista.tradingcore.trading.domain.model.TradingUserProfile> m = new HashMap<>();
            accounts.forEach(a -> m.put(a.userId(), TradingFixtures.tradingUserProfile(a.userId())));
            return m;
        });
        when(cyclePort.findLatestByStrategyIds(any())).thenReturn(cycles);
        when(dcaDetailPort.findActiveByStrategyIds(any())).thenReturn(configs);
        when(exchanges.of(Broker.UPBIT)).thenReturn(exchange);
        runner.runTick(NOW);
    }

    @Test
    void onlyCryptoDcaStrategiesAreRun() {
        Account kis = TradingFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
        Account upbit = TradingFixtures.upbitAccount(UUID.randomUUID(), UUID.randomUUID());
        add(kis, StrategyType.DCA, true);
        add(upbit, StrategyType.INFINITE, true);
        Strategy dca = add(upbit, StrategyType.DCA, true);
        tick();
        verify(executor, times(1)).executeAccount(eq(exchange),
                argThat((List<BatchContext> l) -> l.size() == 1 && l.get(0).strategy().id().equals(dca.id())),
                argThat((Map<UUID, DcaConfig> m) -> m.keySet().equals(java.util.Set.of(dca.id()))), eq(NOW));
    }

    @Test
    void oneAccountFailureDoesNotStopOthers() {
        Account a = TradingFixtures.upbitAccount(new UUID(0, 1), UUID.randomUUID());
        Account b = TradingFixtures.upbitAccount(new UUID(0, 2), UUID.randomUUID());
        add(a, StrategyType.DCA, true);
        Strategy sb = add(b, StrategyType.DCA, true);
        doThrow(new IllegalStateException("boom")).when(synchronizer)
                .sync(any(), argThat((List<BatchContext> l) -> l.get(0).account().id().equals(a.id())), any(), any());
        tick();
        verify(errorReport, times(1)).reportError(any());
        verify(executor).executeAccount(eq(exchange),
                argThat((List<BatchContext> l) -> l.get(0).strategy().id().equals(sb.id())), any(), eq(NOW));
        verify(executor, never()).executeAccount(any(),
                argThat((List<BatchContext> l) -> l.get(0).account().id().equals(a.id())), any(), any());
    }

    @Test
    void syncRunsBeforeExecute() {
        add(TradingFixtures.upbitAccount(UUID.randomUUID(), UUID.randomUUID()), StrategyType.DCA, true);
        tick();
        InOrder order = inOrder(synchronizer, executor);
        order.verify(synchronizer).sync(eq(exchange), anyList(), any(), eq(NOW));
        order.verify(executor).executeAccount(eq(exchange), anyList(), any(), eq(NOW));
    }

    @Test
    void missingConfigIsReportedAndExcluded() {
        Account upbit = TradingFixtures.upbitAccount(UUID.randomUUID(), UUID.randomUUID());
        add(upbit, StrategyType.DCA, false);
        tick();
        verify(errorReport).reportError(any());
        verify(executor, never()).executeAccount(any(), any(), any(), any());
    }
}
