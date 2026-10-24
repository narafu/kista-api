package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CryptoPortfolioServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock AccountPort accountPort;
    @Mock CryptoExchanges exchanges;
    @Mock CryptoExchangePort upbit;
    @Mock CryptoExchangePort binance;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    CryptoPortfolioService service;

    @BeforeEach
    void setUp() {
        service = new CryptoPortfolioService(accountPort, exchanges, strategyPort, strategyCyclePort, cyclePositionPort);
    }

    @Test
    void portfolioEvaluatesHoldingsInKrw() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(new BigDecimal("1000000"), new BigDecimal("5000"),
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(new BigDecimal("0.001"), new BigDecimal("0.0005"), new BigDecimal("140000000")))));
        when(upbit.getCurrentPrices(Set.of(StrategyTicker.BTC))).thenReturn(Map.of(StrategyTicker.BTC, new BigDecimal("150000000")));

        CryptoPortfolio p = service.getCryptoPortfolio(ACCOUNT_ID, USER_ID);

        assertThat(p.currency()).isEqualTo(Currency.KRW);
        assertThat(p.cash()).isEqualByComparingTo("1005000");
        assertThat(p.holdings()).singleElement().satisfies(h -> {
            assertThat(h.quantity()).isEqualByComparingTo("0.0015");
            assertThat(h.evalAmount()).isEqualByComparingTo("225000");
        });
        assertThat(p.totalAsset()).isEqualByComparingTo("1230000");
    }

    @Test
    void binanceUsesUsdtScaleAndLedgerAvg() {
        stubBinanceBtcHolding(new BigDecimal("0.00015985"), new BigDecimal("65000.555"));
        stubLedger(StrategyTicker.BTC, null, new BigDecimal("65000"));

        CryptoPortfolio p = service.getCryptoPortfolio(ACCOUNT_ID, USER_ID);

        assertThat(p.currency()).isEqualTo(Currency.USDT);
        assertThat(p.holdings().getFirst().avgBuyPrice()).isEqualByComparingTo("65000");
        // 0.00015985 × 65000.555 = 10.3903… → scale 2 HALF_UP
        assertThat(p.holdings().getFirst().evalAmount()).isEqualTo(new BigDecimal("10.39"));
    }

    @Test
    void endedCycleLedgerAvgIsIgnored() {
        stubBinanceBtcHolding(new BigDecimal("0.001"), new BigDecimal("65000"));
        stubLedger(StrategyTicker.BTC, LocalDate.of(2026, 10, 1), new BigDecimal("60000"));

        assertThat(service.getCryptoPortfolio(ACCOUNT_ID, USER_ID).holdings().getFirst().avgBuyPrice()).isNull();
        verifyNoInteractions(cyclePositionPort);
    }

    @Test
    void otherTickerStrategyIsNotUsed() {
        stubBinanceBtcHolding(new BigDecimal("0.001"), new BigDecimal("65000"));
        Strategy eth = mock(Strategy.class);
        when(eth.ticker()).thenReturn(StrategyTicker.ETH);
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(eth));

        assertThat(service.getCryptoPortfolio(ACCOUNT_ID, USER_ID).holdings().getFirst().avgBuyPrice()).isNull();
        verifyNoInteractions(strategyCyclePort, cyclePositionPort);
    }

    @Test
    void exchangeAvgPriceWinsAndLedgerIsNotQueried() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(BigDecimal.TEN, BigDecimal.ZERO,
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(new BigDecimal("0.001"), BigDecimal.ZERO, new BigDecimal("150000000")))));
        when(upbit.getCurrentPrices(Set.of(StrategyTicker.BTC))).thenReturn(Map.of(StrategyTicker.BTC, new BigDecimal("150000000")));

        assertThat(service.getCryptoPortfolio(ACCOUNT_ID, USER_ID).holdings().getFirst().avgBuyPrice()).isEqualByComparingTo("150000000");
        verifyNoInteractions(strategyPort);
    }

    @Test
    void noStrategyLeavesAvgNull() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.binanceAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.BINANCE)).thenReturn(binance);
        when(binance.getBalance(any())).thenReturn(new CryptoBalance(BigDecimal.TEN, BigDecimal.ZERO,
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(new BigDecimal("0.001"), BigDecimal.ZERO, null))));
        when(binance.getCurrentPrices(Set.of(StrategyTicker.BTC))).thenReturn(Map.of(StrategyTicker.BTC, new BigDecimal("65000")));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());

        assertThat(service.getCryptoPortfolio(ACCOUNT_ID, USER_ID).holdings().getFirst().avgBuyPrice()).isNull();
    }

    @Test
    void missingPriceExcludedFromTotal() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(new BigDecimal("1000"), BigDecimal.ZERO,
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(new BigDecimal("0.001"), BigDecimal.ZERO, new BigDecimal("140000000")))));
        when(upbit.getCurrentPrices(Set.of(StrategyTicker.BTC))).thenReturn(Map.of());

        CryptoPortfolio p = service.getCryptoPortfolio(ACCOUNT_ID, USER_ID);

        assertThat(p.holdings()).singleElement().satisfies(h -> {
            assertThat(h.currentPrice()).isNull();
            assertThat(h.evalAmount()).isNull();
        });
        assertThat(p.totalAsset()).isEqualByComparingTo("1000");
    }

    @Test
    void emptyHoldingsSkipPriceCall() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(BigDecimal.TEN, BigDecimal.ZERO, Map.of()));

        assertThat(service.getCryptoPortfolio(ACCOUNT_ID, USER_ID).holdings()).isEmpty();
        verify(upbit, never()).getCurrentPrices(any());
    }

    @Test
    void stockAccountIsRejected() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));

        assertThatThrownBy(() -> service.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(exchanges);
    }

    @Test
    void currentPricesQueryAccountExchangeWithoutBalance() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getCurrentPrices(Set.of(StrategyTicker.ETH))).thenReturn(Map.of(StrategyTicker.ETH, new BigDecimal("5000000")));

        assertThat(service.getCurrentPrices(ACCOUNT_ID, USER_ID, List.of(StrategyTicker.ETH)))
                .containsEntry(StrategyTicker.ETH, new BigDecimal("5000000"));
        verify(upbit, never()).getBalance(any());
    }

    @Test
    void currentPricesRejectStockTickerBeforeExchangeCall() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        assertThatThrownBy(() -> service.getCurrentPrices(ACCOUNT_ID, USER_ID, List.of(StrategyTicker.BTC, StrategyTicker.TQQQ)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(exchanges);
    }

    @Test
    void currentPricesRejectEmptyTickers() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        assertThatThrownBy(() -> service.getCurrentPrices(ACCOUNT_ID, USER_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentPricesRejectStockAccount() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));

        assertThatThrownBy(() -> service.getCurrentPrices(ACCOUNT_ID, USER_ID, List.of(StrategyTicker.BTC)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(exchanges);
    }

    // 바이낸스 계좌 + 거래소 평단 없는 BTC 보유
    private void stubBinanceBtcHolding(BigDecimal quantity, BigDecimal price) {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.binanceAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.BINANCE)).thenReturn(binance);
        when(binance.getBalance(any())).thenReturn(new CryptoBalance(new BigDecimal("100.125"), BigDecimal.ZERO,
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(quantity, BigDecimal.ZERO, null))));
        when(binance.getCurrentPrices(Set.of(StrategyTicker.BTC))).thenReturn(Map.of(StrategyTicker.BTC, price));
    }

    // 종목 전략 → 최신 사이클(endDate) → 최신 포지션 평단 원장
    private void stubLedger(StrategyTicker ticker, LocalDate cycleEndDate, BigDecimal avgPrice) {
        UUID strategyId = UUID.randomUUID(), cycleId = UUID.randomUUID();
        Strategy strategy = mock(Strategy.class);
        when(strategy.id()).thenReturn(strategyId);
        when(strategy.ticker()).thenReturn(ticker);
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(strategy));
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, BigDecimal.ZERO, null, LocalDate.of(2026, 9, 1), cycleEndDate, null, null);
        when(strategyCyclePort.findLatestByStrategyIds(Set.of(strategyId))).thenReturn(Map.of(strategyId, cycle));
        if (cycleEndDate != null) return;
        CyclePosition position = mock(CyclePosition.class);
        when(position.avgPrice()).thenReturn(avgPrice);
        when(cyclePositionPort.findLatestByCycleIds(Set.of(cycleId))).thenReturn(Map.of(cycleId, position));
    }
}
