package com.kista.broker.application.service;

import com.kista.broker.application.port.output.BrokerCapabilitiesPort;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BrokerRouter 단위 테스트")
class BrokerRouterTest {

    BrokerCapabilitiesPort kis = mock(BrokerCapabilitiesPort.class);
    BrokerCapabilitiesPort toss = mock(BrokerCapabilitiesPort.class);
    BrokerRouter router;

    @BeforeEach
    void setUp() {
        when(kis.supports()).thenReturn(Broker.KIS);
        when(toss.supports()).thenReturn(Broker.TOSS);
        router = new BrokerRouter(List.of(kis, toss));
    }

    @Test
    @DisplayName("account.broker()에 맞는 어댑터로 위임한다")
    void routesByBroker() {
        BrokerAccountRef kisRef = ref(Broker.KIS);
        BrokerAccountRef tossRef = ref(Broker.TOSS);
        BigDecimal kisPrice = new BigDecimal("10.00");
        BigDecimal tossPrice = new BigDecimal("20.00");
        when(kis.getPrice(StrategyTicker.SOXL, kisRef)).thenReturn(kisPrice);
        when(toss.getPrice(StrategyTicker.SOXL, tossRef)).thenReturn(tossPrice);

        assertThat(router.getPrice(StrategyTicker.SOXL, kisRef)).isSameAs(kisPrice);
        assertThat(router.getPrice(StrategyTicker.SOXL, tossRef)).isSameAs(tossPrice);
        verify(kis).getPrice(StrategyTicker.SOXL, kisRef);
        verify(toss).getPrice(StrategyTicker.SOXL, tossRef);
    }

    @Test
    @DisplayName("account 인자 위치가 다른 포트(LiveBalancePort)도 올바르게 위임한다")
    void routesLiveBalance() {
        BrokerAccountRef tossRef = ref(Broker.TOSS);
        BrokerBalance balance = new BrokerBalance(1, BigDecimal.ONE, BigDecimal.TEN);
        when(toss.getLiveBalance(tossRef, StrategyTicker.SOXL)).thenReturn(balance);

        assertThat(router.getLiveBalance(tossRef, StrategyTicker.SOXL)).isSameAs(balance);
        verify(toss).getLiveBalance(tossRef, StrategyTicker.SOXL);
    }

    @Test
    @DisplayName("등록되지 않은 증권사는 IllegalArgumentException")
    void unknownBroker() {
        assertThatThrownBy(() -> router.getMargin(ref(Broker.MOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("지원하지 않는 증권사: MOCK");
    }

    private BrokerAccountRef ref(Broker broker) {
        return new BrokerAccountRef(UUID.randomUUID(), "key", "secret", "12345678-01", null, broker);
    }
}
