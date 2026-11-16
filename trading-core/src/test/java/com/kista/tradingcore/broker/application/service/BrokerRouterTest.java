package com.kista.tradingcore.broker.application.service;

import com.kista.tradingcore.broker.application.port.output.AdminMarketPricePort;
import com.kista.tradingcore.broker.application.port.output.BrokerCapabilitiesPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BrokerRouter 단위 테스트")
class BrokerRouterTest {

    BrokerCapabilitiesPort meritz = mock(BrokerCapabilitiesPort.class);
    BrokerCapabilitiesPort toss = mock(BrokerCapabilitiesPort.class);
    AdminMarketPricePort admin = mock(AdminMarketPricePort.class);
    BrokerRouter router;

    @BeforeEach
    void setUp() {
        when(meritz.supports()).thenReturn(Broker.MERITZ);
        when(toss.supports()).thenReturn(Broker.TOSS);
        router = new BrokerRouter(List.of(meritz, toss), admin);
    }

    @Test
    @DisplayName("account.broker()에 맞는 어댑터로 위임한다")
    void routesByBroker() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        BrokerAccountRef tossRef = ref(Broker.TOSS);
        BigDecimal meritzPrice = new BigDecimal("10.00");
        BigDecimal tossPrice = new BigDecimal("20.00");
        when(meritz.getPrice(StrategyTicker.SOXL, meritzRef)).thenReturn(meritzPrice);
        when(toss.getPrice(StrategyTicker.SOXL, tossRef)).thenReturn(tossPrice);

        assertThat(router.getPrice(StrategyTicker.SOXL, meritzRef)).isSameAs(meritzPrice);
        assertThat(router.getPrice(StrategyTicker.SOXL, tossRef)).isSameAs(tossPrice);
        verify(meritz).getPrice(StrategyTicker.SOXL, meritzRef);
        verify(toss).getPrice(StrategyTicker.SOXL, tossRef);
    }

    @Test
    @DisplayName("getPrevCloses: 관리자 시세 우선, 누락 종목만 계좌 어댑터로 조회해 합친다")
    void prevClosesAdminFirstThenAccountForMissing() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        when(admin.getPrevCloses(List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("1")));
        when(meritz.getPrevCloses(List.of(StrategyTicker.TQQQ), meritzRef))
                .thenReturn(Map.of(StrategyTicker.TQQQ, new BigDecimal("2")));

        var result = router.getPrevCloses(List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ), meritzRef);

        assertThat(result).containsEntry(StrategyTicker.SOXL, new BigDecimal("1"))
                .containsEntry(StrategyTicker.TQQQ, new BigDecimal("2"));
    }

    @Test
    @DisplayName("getPrevCloses: 관리자가 전부 채우면 계좌 어댑터는 호출하지 않는다")
    void prevClosesAdminCoversAll() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        when(admin.getPrevCloses(List.of(StrategyTicker.SOXL))).thenReturn(Map.of(StrategyTicker.SOXL, BigDecimal.ONE));

        router.getPrevCloses(List.of(StrategyTicker.SOXL), meritzRef);

        verify(meritz, never()).getPrevCloses(any(), any());
    }

    @Test
    @DisplayName("getPrevClose 단건: 관리자에 없으면 계좌 단건 조회로 폴백")
    void prevCloseSingleFallsBackToAccount() {
        BrokerAccountRef tossRef = ref(Broker.TOSS);
        when(admin.getPrevCloses(List.of(StrategyTicker.SOXL))).thenReturn(Map.of());
        when(toss.getPrevClose(StrategyTicker.SOXL, tossRef)).thenReturn(new BigDecimal("9"));

        assertThat(router.getPrevClose(StrategyTicker.SOXL, tossRef)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("getClosingPrices: 관리자 우선, 누락 종목만 계좌 어댑터로 조회한다")
    void closingPricesAdminFirstThenAccountForMissing() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        LocalDate d = LocalDate.of(2026, 10, 14);
        List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        when(admin.getClosingPrices(both, d)).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("1")));
        when(meritz.getClosingPrices(List.of(StrategyTicker.TQQQ), d, meritzRef))
                .thenReturn(Map.of(StrategyTicker.TQQQ, new BigDecimal("2")));

        var result = router.getClosingPrices(both, d, meritzRef);

        assertThat(result).containsEntry(StrategyTicker.SOXL, new BigDecimal("1"))
                .containsEntry(StrategyTicker.TQQQ, new BigDecimal("2"));
    }

    @Test
    @DisplayName("getClosingPrice 단건: 관리자 값이 있으면 그대로, 없으면 계좌 단건 조회로 폴백")
    void closingPriceSingleAdminThenAccount() {
        BrokerAccountRef tossRef = ref(Broker.TOSS);
        LocalDate d = LocalDate.of(2026, 10, 14);
        when(admin.getClosingPrices(List.of(StrategyTicker.SOXL), d)).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("5")));
        when(admin.getClosingPrices(List.of(StrategyTicker.TQQQ), d)).thenReturn(Map.of());
        when(toss.getClosingPrice(StrategyTicker.TQQQ, d, tossRef)).thenReturn(new BigDecimal("7"));

        assertThat(router.getClosingPrice(StrategyTicker.SOXL, d, tossRef)).isEqualByComparingTo("5");
        assertThat(router.getClosingPrice(StrategyTicker.TQQQ, d, tossRef)).isEqualByComparingTo("7");
        verify(toss, never()).getClosingPrice(StrategyTicker.SOXL, d, tossRef);
    }

    @Test
    @DisplayName("getPrices: 관리자 우선, 누락 종목만 계좌 어댑터로 조회해 합친다")
    void pricesAdminFirstThenAccountForMissing() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        when(admin.getPrices(both)).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("1")));
        when(meritz.getPrices(List.of(StrategyTicker.TQQQ), meritzRef)).thenReturn(Map.of(StrategyTicker.TQQQ, new BigDecimal("2")));

        assertThat(router.getPrices(both, meritzRef)).containsEntry(StrategyTicker.SOXL, new BigDecimal("1"))
                .containsEntry(StrategyTicker.TQQQ, new BigDecimal("2"));
    }

    @Test
    @DisplayName("currentPrices: 계좌 어댑터로 직접 보내고 관리자 시세를 부르지 않는다")
    void currentPricesSkipsAdmin() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        List<StrategyTicker> tickers = List.of(StrategyTicker.SOXL);
        when(meritz.currentPrices(meritzRef, tickers)).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("9")));

        assertThat(router.currentPrices(meritzRef, tickers)).containsEntry(StrategyTicker.SOXL, new BigDecimal("9"));
        verify(admin, never()).getPrices(any());
    }

    @Test
    @DisplayName("getPrice 단건: 관리자 값이 있으면 그대로, 없으면 계좌 단건 조회로 폴백")
    void priceSingleAdminThenAccount() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        when(admin.getPrices(List.of(StrategyTicker.SOXL))).thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("5")));
        when(meritz.getPrice(StrategyTicker.TQQQ, meritzRef)).thenReturn(new BigDecimal("7"));

        assertThat(router.getPrice(StrategyTicker.SOXL, meritzRef)).isEqualByComparingTo("5");
        assertThat(router.getPrice(StrategyTicker.TQQQ, meritzRef)).isEqualByComparingTo("7");
        verify(meritz, never()).getPrice(StrategyTicker.SOXL, meritzRef);
    }

    @Test
    @DisplayName("다건: 계좌 보충이 실패해도 관리자 결과가 있으면 부분 결과를 반환한다")
    void multiKeepsAdminPartialWhenAccountFails() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        LocalDate d = LocalDate.of(2026, 10, 14);
        List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        List<StrategyTicker> missing = List.of(StrategyTicker.TQQQ);
        Map<StrategyTicker, BigDecimal> soxl = Map.of(StrategyTicker.SOXL, BigDecimal.ONE);
        when(admin.getPrices(both)).thenReturn(soxl);
        when(admin.getPrevCloses(both)).thenReturn(soxl);
        when(admin.getClosingPrices(both, d)).thenReturn(soxl);
        when(meritz.getPrices(missing, meritzRef)).thenThrow(new RuntimeException("실패"));
        when(meritz.getPrevCloses(missing, meritzRef)).thenThrow(new RuntimeException("실패"));
        when(meritz.getClosingPrices(missing, d, meritzRef)).thenThrow(new RuntimeException("실패"));

        assertThat(router.getPrices(both, meritzRef)).containsOnlyKeys(StrategyTicker.SOXL);
        assertThat(router.getPrevCloses(both, meritzRef)).containsOnlyKeys(StrategyTicker.SOXL);
        assertThat(router.getClosingPrices(both, d, meritzRef)).containsOnlyKeys(StrategyTicker.SOXL);
    }

    @Test
    @DisplayName("다건: 계좌 보충의 인증 예외는 관리자 결과가 있어도 그대로 전파한다")
    void multiPropagatesCredentialEvenWithAdminPartial() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        List<StrategyTicker> missing = List.of(StrategyTicker.TQQQ);
        when(admin.getPrices(both)).thenReturn(Map.of(StrategyTicker.SOXL, BigDecimal.ONE));
        when(meritz.getPrices(missing, meritzRef)).thenThrow(new BrokerCredentialException());

        assertThatThrownBy(() -> router.getPrices(both, meritzRef)).isInstanceOf(BrokerCredentialException.class);
    }

    @Test
    @DisplayName("다건: 계좌 보충의 호출 한도 예외는 관리자 결과가 있으면 부분 결과를 반환하고, 비어 있으면 전파한다")
    void multiKeepsAdminPartialOnRateLimit() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        List<StrategyTicker> both = List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ);
        List<StrategyTicker> missing = List.of(StrategyTicker.TQQQ);
        when(admin.getPrices(both))
                .thenReturn(Map.of(StrategyTicker.SOXL, BigDecimal.ONE))
                .thenReturn(Map.of());
        when(meritz.getPrices(missing, meritzRef)).thenThrow(new BrokerRateLimitException());
        when(meritz.getPrices(both, meritzRef)).thenThrow(new BrokerRateLimitException());

        assertThat(router.getPrices(both, meritzRef)).containsOnlyKeys(StrategyTicker.SOXL);
        assertThatThrownBy(() -> router.getPrices(both, meritzRef)).isInstanceOf(BrokerRateLimitException.class);
    }

    @Test
    @DisplayName("다건: 관리자 결과가 비어 있으면 계좌 조회 예외를 그대로 전파한다")
    void multiPropagatesAccountFailureWhenAdminEmpty() {
        BrokerAccountRef meritzRef = ref(Broker.MERITZ);
        List<StrategyTicker> one = List.of(StrategyTicker.SOXL);
        when(meritz.getPrices(one, meritzRef)).thenThrow(new IllegalStateException("503"));

        assertThatThrownBy(() -> router.getPrices(one, meritzRef)).isInstanceOf(IllegalStateException.class);
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
