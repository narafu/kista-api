package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PreviewDepositCacheTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock LiveBalancePort liveBalancePort;

    PreviewDepositCache cache;
    Account account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void setUp() {
        cache = new PreviewDepositCache(registry);
        lenient().doReturn(liveBalancePort).when(registry).require(any(BrokerAccountRef.class), any());
    }

    @Test
    void getUsdDeposit_returnsFreshValue_onFirstCall() {
        when(liveBalancePort.getLiveBalance(toBrokerRef(account), StrategyTicker.SOXL))
                .thenReturn(new BrokerBalance(0, null, new BigDecimal("1000.00")));

        BigDecimal result = cache.getUsdDeposit(account, StrategyTicker.SOXL);

        assertThat(result).isEqualByComparingTo("1000.00");
        verify(liveBalancePort, times(1)).getLiveBalance(toBrokerRef(account), StrategyTicker.SOXL);
    }

    @Test
    void getUsdDeposit_reusesCachedValue_forSecondCallWithinTtl() {
        when(liveBalancePort.getLiveBalance(toBrokerRef(account), StrategyTicker.SOXL))
                .thenReturn(new BrokerBalance(0, null, new BigDecimal("1000.00")));

        cache.getUsdDeposit(account, StrategyTicker.SOXL);
        BigDecimal second = cache.getUsdDeposit(account, StrategyTicker.TQQQ); // 다른 ticker로 조회해도 계좌 단위로 캐시 재사용

        assertThat(second).isEqualByComparingTo("1000.00");
        verify(liveBalancePort, times(1)).getLiveBalance(any(), any());
    }

    @Test
    void getUsdDeposit_collapsesConcurrentMisses_intoSingleFetch() throws InterruptedException {
        when(liveBalancePort.getLiveBalance(toBrokerRef(account), StrategyTicker.SOXL))
                .thenReturn(new BrokerBalance(0, null, new BigDecimal("1000.00")));
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        IntStream.range(0, threadCount).forEach(i -> pool.submit(() -> {
            BigDecimal result = cache.getUsdDeposit(account, StrategyTicker.SOXL);
            if (result.compareTo(new BigDecimal("1000.00")) == 0) {
                successCount.incrementAndGet();
            }
        }));
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(threadCount);
        verify(liveBalancePort, times(1)).getLiveBalance(toBrokerRef(account), StrategyTicker.SOXL);
    }

    @Test
    void getUsdDeposit_doesNotCache_whenFetchFails() {
        when(liveBalancePort.getLiveBalance(toBrokerRef(account), StrategyTicker.SOXL))
                .thenThrow(new com.kista.broker.domain.model.kis.KisApiException("일시 오류", null))
                .thenReturn(new BrokerBalance(0, null, new BigDecimal("1000.00")));

        assertThatThrownBy(() -> cache.getUsdDeposit(account, StrategyTicker.SOXL))
                .isInstanceOf(com.kista.broker.domain.model.kis.KisApiException.class);
        BigDecimal result = cache.getUsdDeposit(account, StrategyTicker.SOXL);

        assertThat(result).isEqualByComparingTo("1000.00");
        verify(liveBalancePort, times(2)).getLiveBalance(toBrokerRef(account), StrategyTicker.SOXL);
    }

    // broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
    private static BrokerAccountRef toBrokerRef(Account account) {
        return new BrokerAccountRef(
                account.id(), account.appKey(), account.secretKey(),
                account.accountNo(), account.brokerAccountCode(),
                BrokerAccountRef.Broker.valueOf(account.broker().name()));
    }
}
