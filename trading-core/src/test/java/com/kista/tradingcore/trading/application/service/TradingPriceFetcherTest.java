package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import org.mockito.ArgumentCaptor;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradingPriceFetcher 단위 테스트")
class TradingPriceFetcherTest {
    @Mock BrokerPricePort pricePort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PrivacyTradePort privacyTradePort;
    TradingPriceFetcher priceFetcher;
    TradingAccount account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void setUp() {
        priceFetcher = new TradingPriceFetcher(pricePort, eventPublisher, privacyTradePort);
    }

    @Test
    @DisplayName("bulkFetch 결과에 null 값이 섞여 있으면 해당 ticker만 단건 fallback으로 재조회")
    void fetchPrices_bulkResultContainsNull_fallsBackToSingleFetch() {
        Map<StrategyTicker, BigDecimal> bulkResult = new HashMap<>();
        bulkResult.put(StrategyTicker.SOXL, null); // 정상 계약 위반이지만 방어적으로 처리돼야 함
        when(pricePort.getPrices(List.of(StrategyTicker.SOXL), account.brokerRef())).thenReturn(bulkResult);
        when(pricePort.getPrice(StrategyTicker.SOXL, account.brokerRef())).thenReturn(new BigDecimal("25.50"));

        Map<StrategyTicker, BigDecimal> result = priceFetcher.fetchPrices(List.of(StrategyTicker.SOXL), account.brokerRef());

        assertThat(result).containsEntry(StrategyTicker.SOXL, new BigDecimal("25.50"));
    }

    @Test
    @DisplayName("fetchPrevCloses: 일괄+단건 모두 실패해도 이벤트 미발행 — readOnly 미리보기 트랜잭션에서 EPR insert 500 회귀 방지")
    void fetchPrevCloses_allFailed_noEvent() {
        when(pricePort.getPrevCloses(List.of(StrategyTicker.SOXL), account.brokerRef())).thenThrow(new RuntimeException("토큰 발급 실패"));
        when(pricePort.getPrevClose(StrategyTicker.SOXL, account.brokerRef())).thenThrow(new RuntimeException("토큰 발급 실패"));

        Map<StrategyTicker, BigDecimal> result = priceFetcher.fetchPrevCloses(List.of(StrategyTicker.SOXL), account.brokerRef());

        assertThat(result).doesNotContainKey(StrategyTicker.SOXL);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("fetchPrices: 일괄+단건 모두 실패하면 TradingErrorEvent 발행 유지")
    void fetchPrices_allFailed_publishesEvent() {
        when(pricePort.getPrices(List.of(StrategyTicker.SOXL), account.brokerRef())).thenThrow(new RuntimeException("토큰 발급 실패"));
        when(pricePort.getPrice(StrategyTicker.SOXL, account.brokerRef())).thenThrow(new RuntimeException("토큰 발급 실패"));

        priceFetcher.fetchPrices(List.of(StrategyTicker.SOXL), account.brokerRef());

        verify(eventPublisher).publishEvent(any(TradingErrorEvent.class));
    }

    // 배치 컨텍스트 — priceAccount 선택(selectPriceAccount)과 티커 추출에 필요한 최소 mock
    private static final BigDecimal D_SOXL = new BigDecimal("30.00");

    private List<BatchContext> contexts(StrategyTicker... tickers) {
        return java.util.Arrays.stream(tickers).map(t -> {
            BatchContext ctx = mock(BatchContext.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
            when(ctx.strategy().ticker()).thenReturn(t);
            when(ctx.account()).thenReturn(account);
            return ctx;
        }).toList();
    }

    private void verifySingleAlert(String... expectedParts) {
        ArgumentCaptor<TradingErrorEvent> captor = ArgumentCaptor.forClass(TradingErrorEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        for (String part : expectedParts) {
            assertThat(captor.getValue().message()).contains(part);
        }
    }

    @Test
    @DisplayName("loadPriceContext: 현재가·전일종가를 BrokerPricePort(라우터)로 조회해 스냅샷을 만든다")
    void loadPriceContext_buildsSnapshotFromPricePort() {
        when(pricePort.getPrices(List.of(StrategyTicker.SOXL), account.brokerRef()))
                .thenReturn(Map.of(StrategyTicker.SOXL, D_SOXL));
        when(pricePort.getPrevCloses(List.of(StrategyTicker.SOXL), account.brokerRef()))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("29.00")));

        var ctx = priceFetcher.loadPriceContext(contexts(StrategyTicker.SOXL), java.time.LocalDate.now());

        assertThat(ctx.startPriceSnapshots().get(StrategyTicker.SOXL).current()).isEqualByComparingTo(D_SOXL);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("loadPriceContext: 현재가·전일종가 모두 실패해도 알림은 1건, 해당 종목은 스냅샷에서 제외")
    void loadPriceContext_allFailed_singleAlertAndExcluded() {
        when(pricePort.getPrices(any(), any())).thenThrow(new RuntimeException("실패"));
        when(pricePort.getPrice(any(), any())).thenThrow(new RuntimeException("실패"));

        var ctx = priceFetcher.loadPriceContext(contexts(StrategyTicker.SOXL), java.time.LocalDate.now());

        assertThat(ctx.startPriceSnapshots()).doesNotContainKey(StrategyTicker.SOXL);
        verifySingleAlert("[SOXL] 현재가 조회 실패", "관리자·계좌 모두 실패");
        verify(pricePort, never()).getPrevCloses(any(), any()); // 현재가 실패 종목은 전일종가 조회 대상 아님
    }

    @Test
    @DisplayName("loadPriceContext: 현재가 실패 종목은 전일종가 요청에서 빼고, 알림은 한 메시지에 문구를 나눠 담는다")
    void loadPriceContext_currentFailedExcludedFromPrev_splitMessage() {
        BigDecimal tqqq = new BigDecimal("55.00");
        List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        when(pricePort.getPrices(both, account.brokerRef())).thenReturn(Map.of(StrategyTicker.TQQQ, tqqq)); // 부분 결과
        when(pricePort.getPrice(StrategyTicker.SOXL, account.brokerRef())).thenThrow(new RuntimeException("실패"));
        when(pricePort.getPrevCloses(List.of(StrategyTicker.TQQQ), account.brokerRef())).thenThrow(new RuntimeException("실패"));
        when(pricePort.getPrevClose(StrategyTicker.TQQQ, account.brokerRef())).thenThrow(new RuntimeException("실패"));

        var ctx = priceFetcher.loadPriceContext(contexts(StrategyTicker.SOXL, StrategyTicker.TQQQ), java.time.LocalDate.now());

        assertThat(ctx.startPriceSnapshots()).containsOnlyKeys(StrategyTicker.TQQQ);
        verify(pricePort, never()).getPrevCloses(eq(both), any());
        verifySingleAlert("[SOXL] 현재가 조회 실패", "[TQQQ] 전일종가 조회 실패(현재가로 대체)", "관리자·계좌 모두 실패");
    }

    @Test
    @DisplayName("loadPriceContext: 다건 현재가가 null 값이고 단건도 null이면 결과에서 제외하고 알림")
    void loadPriceContext_nullValueBulkAndSingle_excludedAndAlerted() {
        Map<StrategyTicker, BigDecimal> nullValued = new java.util.HashMap<>();
        nullValued.put(StrategyTicker.SOXL, null);
        when(pricePort.getPrices(List.of(StrategyTicker.SOXL), account.brokerRef())).thenReturn(nullValued);
        when(pricePort.getPrice(StrategyTicker.SOXL, account.brokerRef())).thenReturn(null);

        var ctx = priceFetcher.loadPriceContext(contexts(StrategyTicker.SOXL), java.time.LocalDate.now());

        assertThat(ctx.startPriceSnapshots()).isEmpty();
        verifySingleAlert("[SOXL] 현재가 조회 실패");
    }

    @Test
    @DisplayName("loadPriceContext: 전일종가만 끝내 실패하면 current로 대체하고 알림 1건에 포함")
    void loadPriceContext_prevCloseFailed_usesCurrentAndAlerts() {
        when(pricePort.getPrices(List.of(StrategyTicker.SOXL), account.brokerRef())).thenReturn(Map.of(StrategyTicker.SOXL, D_SOXL));
        when(pricePort.getPrevCloses(any(), any())).thenThrow(new RuntimeException("실패"));
        when(pricePort.getPrevClose(any(), any())).thenThrow(new RuntimeException("실패"));

        var ctx = priceFetcher.loadPriceContext(contexts(StrategyTicker.SOXL), java.time.LocalDate.now());

        assertThat(ctx.startPriceSnapshots()).containsEntry(StrategyTicker.SOXL, new PriceSnapshot(D_SOXL, D_SOXL));
        verifySingleAlert("[SOXL] 전일종가 조회 실패(현재가로 대체)");
    }

    @Test
    @DisplayName("reloadPlacementPrices: 부분 결과의 누락분은 단건 fallback, 끝내 실패한 종목만 알림 1건")
    void reloadPlacementPrices_adminThenAccount_singleAlert() {
        var ctxs = contexts(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        var states = ctxs.stream().map(c -> {
            var st = mock(TradingCandidatePlanner.CycleState.class);
            when(st.ctx()).thenReturn(c);
            return st;
        }).toList();
        List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        when(pricePort.getPrices(both, account.brokerRef())).thenReturn(Map.of(StrategyTicker.SOXL, D_SOXL));
        when(pricePort.getPrice(StrategyTicker.TQQQ, account.brokerRef())).thenThrow(new RuntimeException("실패"));

        Map<StrategyTicker, BigDecimal> result = priceFetcher.reloadPlacementPrices(states);

        assertThat(result).containsOnlyKeys(StrategyTicker.SOXL);
        verifySingleAlert("TQQQ", "현재가 조회 실패");
    }
}
