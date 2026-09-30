package com.kista.broker.adapter.out.toss;

import com.kista.broker.application.port.output.BrokerAccountPort;
import com.kista.broker.application.port.output.BrokerAdapterPort;
import com.kista.broker.application.port.output.BrokerMarketCalendarPort;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.application.port.output.BrokerStatisticsPort;
import com.kista.broker.application.port.output.CandlePort;
import com.kista.broker.application.port.output.ExchangeRatePort;
import com.kista.broker.application.port.output.ExecutionPort;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.application.port.output.MarginPort;
import com.kista.broker.application.port.output.PortfolioPort;
import com.kista.broker.application.port.output.SellableQuantityPort;
import com.kista.broker.application.port.output.StockInfoPort;
import com.kista.broker.domain.model.BrokerAccountInfo;
import com.kista.broker.domain.model.BrokerCandle;
import com.kista.broker.domain.model.BrokerStockInfo;
import com.kista.broker.domain.model.ExchangeRateQuote;
import com.kista.broker.domain.model.MarketCalendarDay;
import com.kista.broker.domain.model.toss.TossAccountInfo;
import com.kista.broker.domain.model.toss.TossCandle;
import com.kista.broker.domain.model.toss.TossExchangeRate;
import com.kista.broker.domain.model.toss.TossMarketSession;
import com.kista.broker.domain.model.toss.TossStockInfo;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// Toss 어댑터가 구현해야 할 Capability 집합 고정 — 위임 로직 자체는 TossXxxApi 개별 테스트가 커버
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class TossBrokerAdapterTest {

    @Mock
    private TossHoldingsApi tossHoldingsApi;

    @Mock
    private TossOrderApi tossOrderApi;

    @Mock
    private TossPriceApi tossPriceApi;

    @Mock
    private TossMarketApi tossMarketApi;

    @Mock
    private TossCandleApi tossCandleApi;

    private TossBrokerAdapter adapter() {
        return new TossBrokerAdapter(tossHoldingsApi, tossOrderApi, tossPriceApi, tossMarketApi, tossCandleApi);
    }

    @Test
    @DisplayName("supports()는 TOSS를 반환한다")
    void supportsReturnsToss() {
        assertThat(adapter().supports()).isEqualTo(Broker.TOSS);
    }

    @Test
    @DisplayName("공통 7개 Capability Port를 모두 구현한다")
    void implementsAllCommonCapabilityPorts() {
        TossBrokerAdapter adapter = adapter();

        assertThat(adapter).isInstanceOf(BrokerAdapterPort.class);
        assertThat(adapter).isInstanceOf(PortfolioPort.class);
        assertThat(adapter).isInstanceOf(MarginPort.class);
        assertThat(adapter).isInstanceOf(SellableQuantityPort.class);
        assertThat(adapter).isInstanceOf(ExecutionPort.class);
        assertThat(adapter).isInstanceOf(BrokerOrderCorrectionPort.class);
        assertThat(adapter).isInstanceOf(BrokerPricePort.class);
        assertThat(adapter).isInstanceOf(LiveBalancePort.class);
    }

    @Test
    @DisplayName("Toss 전용 5개 Capability Port를 모두 구현한다")
    void implementsAllTossOnlyCapabilityPorts() {
        TossBrokerAdapter adapter = adapter();

        assertThat(adapter).isInstanceOf(CandlePort.class);
        assertThat(adapter).isInstanceOf(ExchangeRatePort.class);
        assertThat(adapter).isInstanceOf(StockInfoPort.class);
        assertThat(adapter).isInstanceOf(BrokerMarketCalendarPort.class);
        assertThat(adapter).isInstanceOf(BrokerAccountPort.class);
    }

    @Test
    @DisplayName("통계 capability 묶음(BrokerStatisticsPort)을 구현한다")
    void implementsBrokerStatisticsPort() {
        assertThat(adapter()).isInstanceOf(BrokerStatisticsPort.class);
    }

    @Test
    @DisplayName("Toss 응답 타입을 벤더 중립 타입으로 변환해 노출한다")
    void statisticsPortConvertsTossTypesToNeutralTypes() {
        LocalDate day = LocalDate.of(2026, 1, 2);
        OffsetDateTime start = OffsetDateTime.parse("2026-01-02T14:30:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-01-02T21:00:00Z");
        when(tossCandleApi.getCandles("SOXL", "1D", day, day)).thenReturn(List.of(
                new TossCandle(day, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TWO, 7L)));
        when(tossHoldingsApi.getExchangeRate()).thenReturn(new TossExchangeRate(new BigDecimal("1380.5"), new BigDecimal("1375")));
        when(tossPriceApi.getStockInfo(StrategyTicker.SOXL))
                .thenReturn(new TossStockInfo("SOXL", "반도체", "Semi", "NYSE ARCA", "USD", "NORMAL"));
        when(tossMarketApi.getMarketCalendar(day, day)).thenReturn(List.of(
                new TossMarketSession(day, null, new TossMarketSession.SessionHours(start, end), null)));

        TossBrokerAdapter adapter = adapter();

        assertThat(adapter.getCandles("SOXL", "1D", day, day))
                .containsExactly(new BrokerCandle(day, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TWO, 7L));
        assertThat(adapter.getExchangeRate()).isEqualTo(new ExchangeRateQuote(new BigDecimal("1380.5"), new BigDecimal("1375")));
        assertThat(adapter.getStockInfo(StrategyTicker.SOXL))
                .isEqualTo(new BrokerStockInfo("SOXL", "반도체", "Semi", "NYSE ARCA", "USD", "NORMAL"));
        List<MarketCalendarDay> calendar = adapter.getMarketCalendar(day, day);
        assertThat(calendar).hasSize(1);
        assertThat(calendar.get(0).isOpen()).isTrue();
        assertThat(calendar.get(0).preMarket()).isNull();
        assertThat(calendar.get(0).regularMarket()).isEqualTo(new MarketCalendarDay.SessionHours(start, end));
    }

    @Test
    @DisplayName("Toss 계좌 정보를 벤더 중립 BrokerAccountInfo로 변환한다")
    void accountInfoConvertedToNeutral() {
        assertThat(new TossAccountInfo(3, "123-45").toBrokerAccountInfo()).isEqualTo(new BrokerAccountInfo(3, "123-45"));
    }
}
