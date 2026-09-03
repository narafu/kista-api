package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.account.domain.model.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.support.DomainFixtures;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradingPriceFetcher 단위 테스트")
class TradingPriceFetcherTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerPricePort pricePort;
    @Mock ApplicationEventPublisher eventPublisher;
    TradingPriceFetcher priceFetcher;
    Account account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void setUp() {
        priceFetcher = new TradingPriceFetcher(registry, eventPublisher);
        doReturn(pricePort).when(registry).require(any(BrokerAccountRef.class), any());
    }

    @Test
    @DisplayName("bulkFetch 결과에 null 값이 섞여 있으면 해당 ticker만 단건 fallback으로 재조회")
    void fetchPrices_bulkResultContainsNull_fallsBackToSingleFetch() {
        Map<Ticker, BigDecimal> bulkResult = new HashMap<>();
        bulkResult.put(Ticker.SOXL, null); // 정상 계약 위반이지만 방어적으로 처리돼야 함
        when(pricePort.getPrices(List.of(Ticker.SOXL), toBrokerRef(account))).thenReturn(bulkResult);
        when(pricePort.getPrice(Ticker.SOXL, toBrokerRef(account))).thenReturn(new BigDecimal("25.50"));

        Map<Ticker, BigDecimal> result = priceFetcher.fetchPrices(List.of(Ticker.SOXL), account);

        assertThat(result).containsEntry(Ticker.SOXL, new BigDecimal("25.50"));
    }

    @Test
    @DisplayName("fetchPriceSnapshots: bulk·단건 fallback 모두 null이면 NPE 없이 결과에서 제외")
    void fetchPriceSnapshots_bothNull_excludedFromResultWithoutThrowing() {
        Map<Ticker, com.kista.broker.domain.model.PriceSnapshot> bulkResult = new HashMap<>();
        bulkResult.put(Ticker.SOXL, null); // 정상 계약 위반이지만 방어적으로 처리돼야 함(TradingPriceFetcher.java:44-45 NPE 회귀 방지)
        when(pricePort.getPriceSnapshots(List.of(Ticker.SOXL), toBrokerRef(account))).thenReturn(bulkResult);
        when(pricePort.getPriceSnapshot(Ticker.SOXL, toBrokerRef(account))).thenReturn(null);

        Map<Ticker, com.kista.trading.domain.model.PriceSnapshot> result =
                priceFetcher.fetchPriceSnapshots(List.of(Ticker.SOXL), account);

        assertThat(result).doesNotContainKey(Ticker.SOXL);
    }

    // broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
    private static BrokerAccountRef toBrokerRef(Account account) {
        return new BrokerAccountRef(
                account.id(), account.appKey(), account.secretKey(),
                account.accountNo(), account.brokerAccountCode(),
                BrokerAccountRef.Broker.valueOf(account.broker().name()));
    }
}
