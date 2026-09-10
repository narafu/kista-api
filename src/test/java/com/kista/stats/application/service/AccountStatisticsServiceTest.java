package com.kista.stats.application.service;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountStatisticsServiceTest {

    @Mock AccountPort accountPort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock BrokerStatisticsRouter brokerStatisticsRouter;
    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerPricePort pricePort;

    private AccountStatisticsService service;
    private final UUID accountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AccountStatisticsService(
                accountPort, cyclePositionPort, orderPort, brokerStatisticsRouter, registry);
        // 실제 Account record — account.toBrokerRef()가 인스턴스 메서드
        Account account = new Account(accountId, userId, "테스트계좌", "74420614-01", "key", "secret", null, Broker.KIS, null);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
        lenient().doReturn(pricePort).when(registry).require(any(BrokerAccountRef.class), any());
    }

    @Test
    void getPrices_returns_prev_close_not_current() {
        // 전략 생성 화면 티커 목록 가격도 basePrice와 동일 소스(전일종가)를 써야 함 (현재가 API 미사용)
        when(pricePort.getPrevCloses(eq(List.of(StrategyTicker.SOXL)), any(BrokerAccountRef.class)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("89.20")));

        var result = service.getPrices(accountId, userId, List.of(StrategyTicker.SOXL));

        assertThat(result.get(StrategyTicker.SOXL)).isEqualByComparingTo("89.20");
        verify(pricePort, never()).getPrices(any(), any());
        verify(pricePort, never()).getPriceSnapshots(any(), any());
    }
}
