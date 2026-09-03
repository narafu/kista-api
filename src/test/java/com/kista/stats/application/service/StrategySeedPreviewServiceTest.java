package com.kista.stats.application.service;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.privacy.domain.model.PrivacyCurrentBase;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.strategyconfig.domain.model.StrategySeedPreview;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.trading.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.broker.application.service.BrokerAdapterRegistry;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;

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
    Account account;
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
        // 실제 Account record — account.toBrokerRef()가 인스턴스 메서드라 mock(Account.class)로는 null 반환됨
        account = new Account(accountId, userId, "테스트계좌", "74420614-01", "key", "secret", null, Account.Broker.KIS, null);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
        // registry.require(account, BrokerPricePort.class) → pricePort 반환 스텁 (일부 테스트는 도달 전 종료 → lenient)
        lenient().doReturn(pricePort).when(registry).require(any(BrokerAccountRef.class), any());
    }

    @Test
    void infinite_uses_prev_close_not_current() {
        // given: 전일종가 89.20 — 실제 첫 주문(holdings=0)과 동일하게 전일종가를 기준가로 사용해야 함 (현재가 API 미사용)
        when(pricePort.getPrevClose(eq(StrategyTicker.SOXL), any(BrokerAccountRef.class)))
                .thenReturn(new BigDecimal("89.20"));

        // when
        var result = service.strategySeedPreview(accountId, userId, StrategyType.INFINITE, StrategyTicker.SOXL, 20);

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
        var result = service.strategySeedPreview(accountId, userId, StrategyType.PRIVACY, StrategyTicker.SOXL, 0);

        // then
        assertThat(result.skipReason()).isEqualTo("NO_PRIVACY_BASE");
        assertThat(result.basePrice()).isNull();
        assertThat(result.minSeed()).isNull();
    }

    @Test
    void privacy_with_base_returns_min_seed() {
        // given: 기준매매표 있음, currentCycleStart = 5000.00
        PrivacyCurrentBase base = new PrivacyCurrentBase(StrategyTicker.SOXL, new BigDecimal("5000.00"), null);
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.of(base));

        // when
        var result = service.strategySeedPreview(accountId, userId, StrategyType.PRIVACY, StrategyTicker.SOXL, 0);

        // then: PRIVACY minSeed = currentCycleStart / 2
        assertThat(result.basePrice()).isEqualByComparingTo("5000.00");
        assertThat(result.minSeed()).isEqualByComparingTo("2500.00");
        assertThat(result.skipReason()).isNull();
    }

    @Test
    void getPrices_returns_prev_close_not_current() {
        // given: 전략 생성 화면 티커 목록 가격도 basePrice와 동일 소스(전일종가)를 써야 함 (현재가 API 미사용)
        when(pricePort.getPrevCloses(eq(List.of(StrategyTicker.SOXL)), any(BrokerAccountRef.class)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("89.20")));

        // when
        var result = service.getPrices(accountId, userId, List.of(StrategyTicker.SOXL));

        // then
        assertThat(result.get(StrategyTicker.SOXL)).isEqualByComparingTo("89.20");
        verify(pricePort, never()).getPrices(any(), any());
        verify(pricePort, never()).getPriceSnapshots(any(), any());
    }
}
