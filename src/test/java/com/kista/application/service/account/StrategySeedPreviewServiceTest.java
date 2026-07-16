package com.kista.application.service.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.privacy.PrivacyCurrentBase;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategySeedPreview;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.application.service.account.BrokerStatisticsRouter;
import com.kista.application.service.broker.BrokerAdapterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategySeedPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock BrokerStatisticsRouter brokerStatisticsRouter;
    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerPricePort pricePort;  // registry.require(account, BrokerPricePort.class) 반환값
    @Mock PrivacyTradePort privacyTradePort;

    AccountStatisticsService service;
    Account mockAccount;
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(null, null),
                new PrivacyCycleOrderStrategy(null)
        ));
        service = new AccountStatisticsService(
                accountPort, strategyPort, cyclePositionPort, orderPort,
                brokerStatisticsRouter, registry,
                privacyTradePort, cycleStrategies
        );
        mockAccount = mock(Account.class);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(mockAccount);
        // registry.require(account, BrokerPricePort.class) → pricePort 반환 스텁 (일부 테스트는 도달 전 종료 → lenient)
        lenient().doReturn(pricePort).when(registry).require(any(Account.class), any());
    }

    @Test
    void infinite_uses_prev_close_not_current() {
        // given: 전일종가 89.20 — 실제 첫 주문(holdings=0)과 동일하게 전일종가를 기준가로 사용해야 함 (현재가 API 미사용)
        when(pricePort.getPrevClose(Strategy.Ticker.SOXL, mockAccount))
                .thenReturn(new BigDecimal("89.20"));

        // when
        var result = service.strategySeedPreview(accountId, userId, Strategy.Type.INFINITE, Strategy.Ticker.SOXL, 20);

        // then: minSeed = 89.20 * (20 * 2.0) = 3568.00
        assertThat(result.basePrice()).isEqualByComparingTo("89.20");
        assertThat(result.minSeed()).isEqualByComparingTo("3568.00");
        assertThat(result.skipReason()).isNull();
        assertThat(result.ticker()).isEqualTo("SOXL");
        // prevClose 전용 API만 호출 — 현재가 API(Toss라면 별도 호출) 낭비 없음
        verify(pricePort, never()).getPrice(any(), any());
        verify(pricePort, never()).getPriceSnapshot(any(), any());
    }

    @Test
    void privacy_no_base_returns_skip_reason() {
        // given: 기준매매표 없음
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.empty());

        // when
        var result = service.strategySeedPreview(accountId, userId, Strategy.Type.PRIVACY, Strategy.Ticker.SOXL, 0);

        // then
        assertThat(result.skipReason()).isEqualTo("NO_PRIVACY_BASE");
        assertThat(result.basePrice()).isNull();
        assertThat(result.minSeed()).isNull();
    }

    @Test
    void privacy_with_base_returns_min_seed() {
        // given: 기준매매표 있음, currentCycleStart = 5000.00
        PrivacyCurrentBase base = new PrivacyCurrentBase(Ticker.SOXL, new BigDecimal("5000.00"), null);
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.of(base));

        // when
        var result = service.strategySeedPreview(accountId, userId, Strategy.Type.PRIVACY, Strategy.Ticker.SOXL, 0);

        // then: PRIVACY minSeed = currentCycleStart / 2
        assertThat(result.basePrice()).isEqualByComparingTo("5000.00");
        assertThat(result.minSeed()).isEqualByComparingTo("2500.00");
        assertThat(result.skipReason()).isNull();
    }

    @Test
    void getPrices_returns_prev_close_not_current() {
        // given: 전략 생성 화면 티커 목록 가격도 basePrice와 동일 소스(전일종가)를 써야 함 (현재가 API 미사용)
        when(pricePort.getPrevCloses(List.of(Strategy.Ticker.SOXL), mockAccount))
                .thenReturn(Map.of(Strategy.Ticker.SOXL, new BigDecimal("89.20")));

        // when
        var result = service.getPrices(accountId, userId, List.of(Strategy.Ticker.SOXL));

        // then
        assertThat(result.get(Strategy.Ticker.SOXL)).isEqualByComparingTo("89.20");
        verify(pricePort, never()).getPrices(any(), any());
        verify(pricePort, never()).getPriceSnapshots(any(), any());
    }
}
