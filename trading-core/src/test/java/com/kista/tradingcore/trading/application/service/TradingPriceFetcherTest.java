package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.marketdata.MarketDataClient;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.service.support.TradingParallelRunner;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradingPriceFetcher 단위 테스트")
class TradingPriceFetcherTest {
    @Mock BrokerPricePort pricePort;
    @Mock MarketDataClient marketData;
    @Mock MarketCalendarPort calendar;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PrivacyTradePort privacyTradePort;
    TradingPriceFetcher priceFetcher;

    TradingAccount accountA = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
    TradingAccount accountB = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());

    static final LocalDate KST_TRADE_DATE = LocalDate.of(2026, 11, 24);
    static final LocalDate US_SESSION = LocalDate.of(2026, 11, 23);
    static final BigDecimal P_A = new BigDecimal("30.00");
    static final BigDecimal P_B = new BigDecimal("31.00");
    static final BigDecimal PREV = new BigDecimal("29.00");

    @BeforeEach
    void setUp() {
        // 병렬 상한 2 — 실제 배치와 같은 가상 스레드 경로
        priceFetcher = new TradingPriceFetcher(pricePort, marketData, calendar, new TradingParallelRunner(2),
                eventPublisher, privacyTradePort);
        lenient().when(calendar.usSessionDateOf(KST_TRADE_DATE)).thenReturn(US_SESSION);
    }

    private static BatchContext ctx(TradingAccount account, StrategyTicker ticker) {
        BatchContext ctx = mock(BatchContext.class, Answers.RETURNS_DEEP_STUBS);
        when(ctx.strategy().ticker()).thenReturn(ticker);
        when(ctx.account()).thenReturn(account);
        return ctx;
    }

    private static TradingCandidatePlanner.CycleState state(BatchContext ctx) {
        TradingCandidatePlanner.CycleState st = mock(TradingCandidatePlanner.CycleState.class);
        when(st.ctx()).thenReturn(ctx);
        return st;
    }

    private String singleAlertMessage() {
        ArgumentCaptor<TradingErrorEvent> captor = ArgumentCaptor.forClass(TradingErrorEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        return captor.getValue().message();
    }

    @Test
    @DisplayName("같은 종목을 가진 두 계좌 중 한 계좌만 현재가가 실패하면 그 계좌 키만 빠진다")
    void 같은_종목을_가진_두_계좌_중_한_계좌만_현재가가_실패하면_그_계좌_키만_빠진다() throws Exception {
        when(pricePort.currentPrices(eq(accountA.brokerRef()), any())).thenThrow(new IllegalStateException("boom"));
        when(pricePort.currentPrices(eq(accountB.brokerRef()), any())).thenReturn(Map.of(StrategyTicker.SOXL, P_B));
        when(marketData.closesBefore(anyCollection(), eq(US_SESSION))).thenReturn(Map.of(StrategyTicker.SOXL, PREV));

        var priceCtx = priceFetcher.loadPriceContext(
                List.of(ctx(accountA, StrategyTicker.SOXL), ctx(accountB, StrategyTicker.SOXL)), KST_TRADE_DATE);

        assertThat(priceCtx.startPriceSnapshots())
                .containsOnlyKeys(new PriceKey(accountB.id(), StrategyTicker.SOXL))
                .containsEntry(new PriceKey(accountB.id(), StrategyTicker.SOXL), new PriceSnapshot(P_B, PREV));
        assertThat(singleAlertMessage()).contains("계좌 1개").contains("SOXL");
    }

    @Test
    @DisplayName("계좌마다 자기 전략 종목만 조회한다")
    void 계좌마다_자기_전략_종목만_조회한다() throws Exception {
        when(pricePort.currentPrices(accountA.brokerRef(), List.of(StrategyTicker.SOXL))).thenReturn(Map.of(StrategyTicker.SOXL, P_A));
        when(pricePort.currentPrices(accountB.brokerRef(), List.of(StrategyTicker.TQQQ))).thenReturn(Map.of(StrategyTicker.TQQQ, P_B));
        when(marketData.closesBefore(anyCollection(), eq(US_SESSION)))
                .thenReturn(Map.of(StrategyTicker.SOXL, PREV, StrategyTicker.TQQQ, PREV));

        var priceCtx = priceFetcher.loadPriceContext(
                List.of(ctx(accountA, StrategyTicker.SOXL), ctx(accountB, StrategyTicker.TQQQ)), KST_TRADE_DATE);

        verify(pricePort, times(1)).currentPrices(accountA.brokerRef(), List.of(StrategyTicker.SOXL));
        verify(pricePort, times(1)).currentPrices(accountB.brokerRef(), List.of(StrategyTicker.TQQQ));
        verify(pricePort, times(2)).currentPrices(any(), any()); // 그 밖의 인자로는 0회
        assertThat(priceCtx.startPriceSnapshots()).containsOnlyKeys(
                new PriceKey(accountA.id(), StrategyTicker.SOXL), new PriceKey(accountB.id(), StrategyTicker.TQQQ));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("현재가 실패를 다른 출처로 대체하지 않는다")
    void 현재가_실패를_다른_출처로_대체하지_않는다() throws Exception {
        when(pricePort.currentPrices(any(), any())).thenThrow(new IllegalStateException("boom"));

        var priceCtx = priceFetcher.loadPriceContext(List.of(ctx(accountA, StrategyTicker.SOXL)), KST_TRADE_DATE);

        assertThat(priceCtx.startPriceSnapshots()).isEmpty();
        verify(marketData, never()).latestPrices(any());
        verify(pricePort, never()).getPrices(any(), any());
        verify(pricePort, never()).getPrice(any(), any());
    }

    @Test
    @DisplayName("전일종가는 KST 거래일의 미국 세션일로 조회한다")
    void 전일종가는_KST_거래일의_미국_세션일로_조회한다() throws Exception {
        when(pricePort.currentPrices(any(), any())).thenReturn(Map.of(StrategyTicker.SOXL, P_A));
        when(marketData.closesBefore(anyCollection(), any())).thenReturn(Map.of(StrategyTicker.SOXL, PREV));

        priceFetcher.loadPriceContext(List.of(ctx(accountA, StrategyTicker.SOXL)), KST_TRADE_DATE);

        verify(marketData, times(1)).closesBefore(anyCollection(), eq(US_SESSION));
        verify(marketData, times(1)).closesBefore(anyCollection(), any());
    }

    @Test
    @DisplayName("전일종가가 없으면 그 계좌 현재가로 대체하고 알림을 1건 보낸다")
    void 전일종가가_없으면_그_계좌_현재가로_대체하고_알림을_1건_보낸다() throws Exception {
        when(pricePort.currentPrices(eq(accountA.brokerRef()), any())).thenReturn(Map.of(StrategyTicker.SOXL, P_A));
        when(pricePort.currentPrices(eq(accountB.brokerRef()), any())).thenReturn(Map.of(StrategyTicker.SOXL, P_B));
        when(marketData.closesBefore(anyCollection(), eq(US_SESSION))).thenReturn(Map.of());

        var priceCtx = priceFetcher.loadPriceContext(
                List.of(ctx(accountA, StrategyTicker.SOXL), ctx(accountB, StrategyTicker.SOXL)), KST_TRADE_DATE);

        assertThat(priceCtx.startPriceSnapshots())
                .containsEntry(new PriceKey(accountA.id(), StrategyTicker.SOXL), new PriceSnapshot(P_A, P_A))
                .containsEntry(new PriceKey(accountB.id(), StrategyTicker.SOXL), new PriceSnapshot(P_B, P_B));
        assertThat(singleAlertMessage()).contains("[SOXL] 전일종가 조회 실패(현재가로 대체)");
    }

    @Test
    @DisplayName("현재가와 전일종가가 모두 실패해도 알림은 1건이다")
    void 현재가와_전일종가가_모두_실패해도_알림은_1건이다() throws Exception {
        when(pricePort.currentPrices(eq(accountA.brokerRef()), any())).thenThrow(new IllegalStateException("boom"));
        when(pricePort.currentPrices(eq(accountB.brokerRef()), any())).thenReturn(Map.of(StrategyTicker.TQQQ, P_B));
        when(marketData.closesBefore(anyCollection(), eq(US_SESSION))).thenThrow(new IllegalStateException("alpaca down"));

        var priceCtx = priceFetcher.loadPriceContext(
                List.of(ctx(accountA, StrategyTicker.SOXL), ctx(accountB, StrategyTicker.TQQQ)), KST_TRADE_DATE);

        assertThat(priceCtx.startPriceSnapshots()).containsOnlyKeys(new PriceKey(accountB.id(), StrategyTicker.TQQQ));
        assertThat(singleAlertMessage())
                .contains("현재가 조회 실패: 계좌 1개 [SOXL]")
                .contains("[TQQQ] 전일종가 조회 실패(현재가로 대체)");
    }

    @Test
    @DisplayName("접수 직전 재조회도 계좌별이다")
    void 접수_직전_재조회도_계좌별이다() throws Exception {
        when(pricePort.currentPrices(accountA.brokerRef(), List.of(StrategyTicker.SOXL))).thenReturn(Map.of(StrategyTicker.SOXL, P_A));
        when(pricePort.currentPrices(accountB.brokerRef(), List.of(StrategyTicker.SOXL))).thenReturn(Map.of());

        Map<PriceKey, BigDecimal> prices = priceFetcher.reloadPlacementPrices(List.of(
                state(ctx(accountA, StrategyTicker.SOXL)), state(ctx(accountB, StrategyTicker.SOXL))));

        assertThat(prices).containsOnlyKeys(new PriceKey(accountA.id(), StrategyTicker.SOXL))
                .containsEntry(new PriceKey(accountA.id(), StrategyTicker.SOXL), P_A);
        verify(pricePort, times(2)).currentPrices(any(), any());
        assertThat(singleAlertMessage()).contains("현재가 조회 실패: 계좌 1개 [SOXL]");
    }

    @Test
    @DisplayName("확정종가는 세션일로 조회하고 없는 종목은 키가 없다")
    void 확정종가는_세션일로_조회하고_없는_종목은_키가_없다() {
        when(marketData.confirmedBars(anyCollection(), eq(US_SESSION))).thenReturn(Map.of(StrategyTicker.SOXL,
                new BrokerCandle(US_SESSION, P_A, P_A, P_A, new BigDecimal("30.50"), 100)));

        Map<StrategyTicker, BigDecimal> closes = priceFetcher.fetchClosingPrices(
                List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ), KST_TRADE_DATE);

        assertThat(closes).containsOnlyKeys(StrategyTicker.SOXL).containsEntry(StrategyTicker.SOXL, new BigDecimal("30.50"));
        verify(eventPublisher, never()).publishEvent(any());
        verify(pricePort, never()).currentPrices(any(), any());
    }

    @Test
    @DisplayName("fetchPrevCloses: 공용 시세 마지막 확정 종가, 실패해도 알림 없음 — readOnly 미리보기 트랜잭션 EPR insert 500 방지")
    void fetchPrevCloses_failure_noEvent() {
        when(marketData.latestCloses(anyCollection())).thenThrow(new IllegalStateException("alpaca down"));

        assertThat(priceFetcher.fetchPrevCloses(List.of(StrategyTicker.SOXL))).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
    }
}
