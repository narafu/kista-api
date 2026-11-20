package com.kista.tradingcore.trading.application.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.marketdata.MarketDataClient;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PriceSourceComparison 단위 테스트")
class PriceSourceComparisonTest {

    MarketDataClient alpaca = mock(MarketDataClient.class);
    BrokerPricePort port = mock(BrokerPricePort.class);
    PriceSourceComparison comparison = new PriceSourceComparison(alpaca, port, Duration.ZERO);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    Logger logger = (Logger) LoggerFactory.getLogger(PriceSourceComparison.class);

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("old/new가 있으면 형식대로 한 줄과 diffPct(4자리)를 남긴다")
    void logsLineWithDiff() {
        TradingAccount meritz = account(Broker.MERITZ);
        when(port.currentPrices(meritz.brokerRef(), List.of(StrategyTicker.SOXL)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("101")));

        comparison.current(List.of(ctx(meritz, StrategyTicker.SOXL)), Map.of(StrategyTicker.SOXL, new BigDecimal("100")));

        assertThat(messages()).containsExactly("[시세 비교] ticker=SOXL kind=current old=100 new=101 diffPct=1.0000");
    }

    @Test
    @DisplayName("new가 없으면 null로 한 줄을 남긴다")
    void logsNullWhenNewMissing() {
        TradingAccount meritz = account(Broker.MERITZ);
        when(port.currentPrices(any(), any())).thenReturn(Map.of());

        comparison.current(List.of(ctx(meritz, StrategyTicker.SOXL)), Map.of(StrategyTicker.SOXL, new BigDecimal("100")));

        assertThat(messages()).containsExactly("[시세 비교] ticker=SOXL kind=current old=100 new=null diffPct=null");
    }

    @Test
    @DisplayName("새 출처가 예외를 던져도 전파하지 않는다")
    void swallowsFailure() {
        when(port.currentPrices(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> comparison.current(List.of(ctx(account(Broker.MERITZ), StrategyTicker.SOXL)), Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("메리츠 계좌가 여럿이면 id가 가장 작은 계좌의 종목만 그 계좌로 조회한다")
    void picksEarliestMeritzAccount() {
        TradingAccount early = account(Broker.MERITZ, new UUID(1, 0));
        TradingAccount late = account(Broker.MERITZ, new UUID(2, 0));
        when(port.currentPrices(any(), any())).thenReturn(Map.of());

        comparison.current(List.of(ctx(late, StrategyTicker.TQQQ), ctx(early, StrategyTicker.SOXL)), Map.of());

        verify(port).currentPrices(early.brokerRef(), List.of(StrategyTicker.SOXL));
        verify(port, never()).currentPrices(late.brokerRef(), List.of(StrategyTicker.TQQQ));
    }

    @Test
    @DisplayName("메리츠 계좌가 없으면 currentPrices를 부르지 않는다")
    void skipsWithoutMeritz() {
        comparison.current(List.of(ctx(account(Broker.TOSS), StrategyTicker.SOXL), ctx(account(Broker.MOCK), StrategyTicker.TQQQ)), Map.of());

        verify(port, never()).currentPrices(any(), any());
        assertThat(messages()).isEmpty();
    }

    @Test
    @DisplayName("afterLoad: 전일 종가 조회가 예외여도 현재가 비교 로그는 남는다")
    void afterLoadKeepsCurrentWhenPrevCloseFails() {
        TradingAccount meritz = account(Broker.MERITZ);
        when(alpaca.closesBefore(any(), any())).thenThrow(new IllegalStateException("boom"));
        when(port.currentPrices(any(), any())).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("101")));
        var ctx = new TradingPriceFetcher.PriceContext(List.of(StrategyTicker.SOXL), meritz.brokerRef(),
                Map.of(StrategyTicker.SOXL, new com.kista.tradingcore.broker.domain.model.PriceSnapshot(new BigDecimal("100"), new BigDecimal("99"))), null);

        comparison.afterLoad(List.of(ctx(meritz, StrategyTicker.SOXL)), ctx, LocalDate.of(2026, 11, 13));

        assertThat(messages()).containsExactly("[시세 비교] ticker=SOXL kind=current old=100 new=101 diffPct=1.0000");
    }

    @Test
    @DisplayName("closes: 가상 스레드에서 close+10, close+20 두 줄을 남긴다")
    void closesLogsBothKinds() throws Exception {
        BrokerCandle bar = new BrokerCandle(LocalDate.of(2026, 11, 13), null, null, null, new BigDecimal("50"), 0);
        when(alpaca.confirmedBars(any(), any())).thenReturn(Map.of(StrategyTicker.SOXL, bar));

        comparison.closes(List.of(StrategyTicker.SOXL), Map.of(StrategyTicker.SOXL, new BigDecimal("50")), LocalDate.of(2026, 11, 13));

        // 가상 스레드 완료를 짧게 폴링한다
        for (int i = 0; i < 100 && messages().size() < 2; i++) Thread.sleep(50);
        assertThat(messages()).containsExactly(
                "[시세 비교] ticker=SOXL kind=close+10 old=50 new=50 diffPct=0.0000",
                "[시세 비교] ticker=SOXL kind=close+20 old=50 new=50 diffPct=0.0000");
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static TradingAccount account(Broker broker) {
        return account(broker, UUID.randomUUID());
    }

    private static TradingAccount account(Broker broker, UUID id) {
        return new TradingAccount(id, UUID.randomUUID(), "nick", new BrokerAccountRef(id, "k", "s", "12345678-01", null, broker));
    }

    private static BatchContext ctx(TradingAccount account, StrategyTicker ticker) {
        Strategy strategy = mock(Strategy.class);
        when(strategy.ticker()).thenReturn(ticker);
        return new BatchContext(strategy, null, account, null);
    }
}
