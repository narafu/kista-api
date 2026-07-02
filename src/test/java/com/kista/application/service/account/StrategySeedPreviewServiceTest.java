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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
    void infinite_uses_current_price() {
        // given: 현재가 30.00, divisionCount=20
        when(pricePort.getPrice(Strategy.Ticker.SOXL, mockAccount))
                .thenReturn(new BigDecimal("30.00"));

        // when
        var result = service.strategySeedPreview(accountId, userId, Strategy.Type.INFINITE, Strategy.Ticker.SOXL, 20);

        // then: minSeed = 30.00 * (20 * 2.0) = 1200.00
        assertThat(result.basePrice()).isEqualByComparingTo("30.00");
        assertThat(result.minSeed()).isEqualByComparingTo("1200.00");
        assertThat(result.skipReason()).isNull();
        assertThat(result.ticker()).isEqualTo("SOXL");
    }

    @Test
    void privacy_no_base_returns_skip_reason() {
        // given: 기준매매표 없음 — findCurrentBase()는 FIDA 선행 업로드 포함
        when(privacyTradePort.findCurrentBase()).thenReturn(Optional.empty());

        // when
        var result = service.strategySeedPreview(accountId, userId, Strategy.Type.PRIVACY, Strategy.Ticker.SOXL, 0);

        // then
        assertThat(result.skipReason()).isEqualTo("NO_PRIVACY_BASE");
        assertThat(result.basePrice()).isNull();
        assertThat(result.minSeed()).isNull();
    }

    @Test
    void privacy_with_base_returns_min_seed() {
        // given: 기준매매표 있음 (FIDA 선행 업로드 포함), currentCycleStart = 5000.00
        PrivacyCurrentBase base = new PrivacyCurrentBase(Ticker.SOXL, new BigDecimal("5000.00"), null);
        when(privacyTradePort.findCurrentBase()).thenReturn(Optional.of(base));

        // when
        var result = service.strategySeedPreview(accountId, userId, Strategy.Type.PRIVACY, Strategy.Ticker.SOXL, 0);

        // then: PRIVACY minSeed = currentCycleStart / 2
        assertThat(result.basePrice()).isEqualByComparingTo("5000.00");
        assertThat(result.minSeed()).isEqualByComparingTo("2500.00");
        assertThat(result.skipReason()).isNull();
    }
}
