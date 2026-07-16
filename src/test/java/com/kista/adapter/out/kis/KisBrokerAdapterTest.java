package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.broker.BrokerAccountPort;
import com.kista.domain.port.out.broker.BrokerAdapterPort;
import com.kista.domain.port.out.broker.BrokerMarketCalendarPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.port.out.broker.CandlePort;
import com.kista.domain.port.out.broker.ExchangeRatePort;
import com.kista.domain.port.out.broker.ExecutionPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.port.out.broker.PortfolioPort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.domain.port.out.broker.StockInfoPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

// KIS 어댑터가 구현해야 할 Capability 집합 고정 — 위임 로직 자체는 KisXxxApi 개별 테스트가 커버
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class KisBrokerAdapterTest {

    @Mock
    private KisTradingApi kisTradingApi;

    @Mock
    private KisOrderApi kisOrderApi;

    @Mock
    private KisPriceApi kisPriceApi;

    private KisBrokerAdapter adapter() {
        return new KisBrokerAdapter(kisTradingApi, kisOrderApi, kisPriceApi);
    }

    @Test
    @DisplayName("supports()는 KIS를 반환한다")
    void supportsReturnsKis() {
        assertThat(adapter().supports()).isEqualTo(Account.Broker.KIS);
    }

    @Test
    @DisplayName("공통 7개 Capability Port를 모두 구현한다")
    void implementsAllCommonCapabilityPorts() {
        KisBrokerAdapter adapter = adapter();

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
    @DisplayName("Toss 전용 Port는 구현하지 않는다")
    void doesNotImplementTossOnlyPorts() {
        KisBrokerAdapter adapter = adapter();

        assertThat(adapter).isNotInstanceOf(CandlePort.class);
        assertThat(adapter).isNotInstanceOf(ExchangeRatePort.class);
        assertThat(adapter).isNotInstanceOf(StockInfoPort.class);
        assertThat(adapter).isNotInstanceOf(BrokerMarketCalendarPort.class);
        assertThat(adapter).isNotInstanceOf(BrokerAccountPort.class);
    }
}
