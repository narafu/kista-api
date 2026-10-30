package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.tradingcore.broker.application.port.output.BrokerAdapterPort;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.BrokerStatisticsPort;
import com.kista.tradingcore.broker.application.port.output.CandlePort;
import com.kista.tradingcore.broker.application.port.output.ExchangeRatePort;
import com.kista.tradingcore.broker.application.port.output.ExecutionPort;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.broker.application.port.output.PortfolioPort;
import com.kista.tradingcore.broker.application.port.output.SellableQuantityPort;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.ExchangeRateQuote;
import com.kista.tradingcore.broker.domain.model.toss.TossCandle;
import com.kista.tradingcore.broker.domain.model.toss.TossExchangeRate;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private TossCandleApi tossCandleApi;

    private TossBrokerAdapter adapter() {
        return new TossBrokerAdapter(tossHoldingsApi, tossOrderApi, tossPriceApi, tossCandleApi);
    }

    @Test
    @DisplayName("supports()는 TOSS를 반환한다")
    void supportsReturnsToss() {
        assertThat(adapter().supports()).isEqualTo(Broker.TOSS);
    }

    @Test
    @DisplayName("공통 6개 Capability Port를 모두 구현한다")
    void implementsAllCommonCapabilityPorts() {
        TossBrokerAdapter adapter = adapter();

        assertThat(adapter).isInstanceOf(BrokerAdapterPort.class);
        assertThat(adapter).isInstanceOf(PortfolioPort.class);
        assertThat(adapter).isInstanceOf(MarginPort.class);
        assertThat(adapter).isInstanceOf(SellableQuantityPort.class);
        assertThat(adapter).isInstanceOf(ExecutionPort.class);
        assertThat(adapter).isInstanceOf(BrokerOrderCorrectionPort.class);
        assertThat(adapter).isInstanceOf(BrokerPricePort.class);
    }

    @Test
    @DisplayName("Toss 전용 2개 Capability Port를 모두 구현한다")
    void implementsAllTossOnlyCapabilityPorts() {
        TossBrokerAdapter adapter = adapter();

        assertThat(adapter).isInstanceOf(CandlePort.class);
        assertThat(adapter).isInstanceOf(ExchangeRatePort.class);
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
        when(tossCandleApi.getCandles("SOXL", "1D", day, day)).thenReturn(List.of(
                new TossCandle(day, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TWO, 7L)));
        when(tossHoldingsApi.getExchangeRate()).thenReturn(new TossExchangeRate(new BigDecimal("1380.5"), new BigDecimal("1375")));

        TossBrokerAdapter adapter = adapter();

        assertThat(adapter.getCandles("SOXL", "1D", day, day))
                .containsExactly(new BrokerCandle(day, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TWO, 7L));
        assertThat(adapter.getExchangeRate()).isEqualTo(new ExchangeRateQuote(new BigDecimal("1380.5"), new BigDecimal("1375")));
    }
}
