package com.kista.broker.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.port.out.BrokerAccountPort;
import com.kista.broker.domain.port.out.BrokerAdapterPort;
import com.kista.broker.domain.port.out.BrokerMarketCalendarPort;
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
import com.kista.broker.domain.port.out.BrokerPricePort;
import com.kista.broker.domain.port.out.CandlePort;
import com.kista.broker.domain.port.out.ExchangeRatePort;
import com.kista.broker.domain.port.out.ExecutionPort;
import com.kista.broker.domain.port.out.LiveBalancePort;
import com.kista.broker.domain.port.out.MarginPort;
import com.kista.broker.domain.port.out.PortfolioPort;
import com.kista.broker.domain.port.out.SellableQuantityPort;
import com.kista.broker.domain.port.out.StockInfoPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(adapter().supports()).isEqualTo(Account.Broker.TOSS);
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
}
