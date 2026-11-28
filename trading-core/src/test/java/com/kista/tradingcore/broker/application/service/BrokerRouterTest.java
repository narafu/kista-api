package com.kista.tradingcore.broker.application.service;

import com.kista.tradingcore.broker.application.port.output.BrokerCapabilitiesPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BrokerRouter 단위 테스트")
class BrokerRouterTest {

    BrokerCapabilitiesPort meritz = mock(BrokerCapabilitiesPort.class);
    BrokerCapabilitiesPort toss = mock(BrokerCapabilitiesPort.class);
    BrokerRouter router;

    @BeforeEach
    void setUp() {
        when(meritz.supports()).thenReturn(Broker.MERITZ);
        when(toss.supports()).thenReturn(Broker.TOSS);
        router = new BrokerRouter(List.of(meritz, toss));
    }

    @Test
    @DisplayName("currentPrices: account.broker()에 맞는 어댑터로 위임한다")
    void currentPricesRoutesByBroker() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        BrokerAccountRef tossRef = ref(Broker.TOSS);
        List<StrategyTicker> tickers = List.of(StrategyTicker.SOXL);
        when(meritz.currentPrices(meritzRef, tickers)).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("9")));
        when(toss.currentPrices(tossRef, tickers)).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("20")));

        assertThat(router.currentPrices(meritzRef, tickers)).containsEntry(StrategyTicker.SOXL, new BigDecimal("9"));
        assertThat(router.currentPrices(tossRef, tickers)).containsEntry(StrategyTicker.SOXL, new BigDecimal("20"));
    }

    @Test
    @DisplayName("등록되지 않은 증권사는 IllegalArgumentException")
    void unknownBroker() {
        assertThatThrownBy(() -> router.getUsdBuyableAmount(ref(Broker.MOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("지원하지 않는 증권사입니다.");
    }

    private BrokerAccountRef ref(Broker broker) {
        return new BrokerAccountRef(UUID.randomUUID(), "key", "secret", "12345678-01", null, broker);
    }
}
