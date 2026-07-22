package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.broker.LiveBalancePort;
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
        lenient().doReturn(liveBalancePort).when(registry).require(any(Account.class), any());
    }

    @Test
    void getUsdDeposit_returnsFreshValue_onFirstCall() {
        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));

        BigDecimal result = cache.getUsdDeposit(account, Ticker.SOXL);

        assertThat(result).isEqualByComparingTo("1000.00");
        verify(liveBalancePort, times(1)).getLiveBalance(account, Ticker.SOXL);
    }

    @Test
    void getUsdDeposit_reusesCachedValue_forSecondCallWithinTtl() {
        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));

        cache.getUsdDeposit(account, Ticker.SOXL);
        BigDecimal second = cache.getUsdDeposit(account, Ticker.TQQQ); // 다른 ticker로 조회해도 계좌 단위로 캐시 재사용

        assertThat(second).isEqualByComparingTo("1000.00");
        verify(liveBalancePort, times(1)).getLiveBalance(any(), any());
    }

    @Test
    void getUsdDeposit_collapsesConcurrentMisses_intoSingleFetch() throws InterruptedException {
        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        IntStream.range(0, threadCount).forEach(i -> pool.submit(() -> {
            BigDecimal result = cache.getUsdDeposit(account, Ticker.SOXL);
            if (result.compareTo(new BigDecimal("1000.00")) == 0) {
                successCount.incrementAndGet();
            }
        }));
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(threadCount);
        verify(liveBalancePort, times(1)).getLiveBalance(account, Ticker.SOXL);
    }

    @Test
    void getUsdDeposit_doesNotCache_whenFetchFails() {
        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenThrow(new com.kista.domain.model.kis.KisApiException("일시 오류", null))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));

        assertThatThrownBy(() -> cache.getUsdDeposit(account, Ticker.SOXL))
                .isInstanceOf(com.kista.domain.model.kis.KisApiException.class);
        BigDecimal result = cache.getUsdDeposit(account, Ticker.SOXL);

        assertThat(result).isEqualByComparingTo("1000.00");
        verify(liveBalancePort, times(2)).getLiveBalance(account, Ticker.SOXL);
    }
}
