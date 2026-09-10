package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.privacy.domain.model.PrivacyCurrentBase;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.account.application.port.output.AccountPort;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.broker.application.service.BrokerAdapterRegistry;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// stats AccountStatisticsService에서 이관 — 시드 미리보기는 등록 정책(minRequiredDeposit) 계산과 동일 경로라 trading 소유
@ExtendWith(MockitoExtension.class)
class StrategySeedPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerPricePort pricePort;  // registry.require(ref, BrokerPricePort.class) 반환값
    @Mock PrivacyTradePort privacyTradePort;

    private StrategyService service;
    private Account account;
    private final UUID accountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // InfiniteCycleOrderStrategy/PrivacyCycleOrderStrategy는 minRequiredDeposit()에서 협력자를 건드리지 않아 null 주입 안전
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(null, null),
                new PrivacyCycleOrderStrategy(null)
        ));
        // 시드 미리보기가 쓰는 협력자만 실제 주입, 나머지는 미사용이라 null
        StrategyHistoryQueryService historyQueryService = new StrategyHistoryQueryService(
                accountPort, null, null, null, cycleStrategies, privacyTradePort, registry);
        service = new StrategyService(
                null, null, null, null, null, null, null,
                accountPort, null, historyQueryService);
        // 실제 Account record — account.toBrokerRef()가 인스턴스 메서드라 mock(Account.class)로는 null 반환됨
        account = new Account(accountId, userId, "테스트계좌", "74420614-01", "key", "secret", null, Broker.KIS, null);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
        lenient().doReturn(pricePort).when(registry).require(any(BrokerAccountRef.class), any());
    }

    @Test
    void infinite_uses_prev_close_not_current() {
        // given: 전일종가 89.20 — 실제 첫 주문(holdings=0)과 동일하게 전일종가를 기준가로 사용해야 함 (현재가 API 미사용)
        when(pricePort.getPrevClose(eq(StrategyTicker.SOXL), any(BrokerAccountRef.class)))
                .thenReturn(new BigDecimal("89.20"));

        var result = service.strategySeedPreview(accountId, userId, StrategyType.INFINITE, StrategyTicker.SOXL, 20);

        // then: minSeed = 89.20 * (20 * 2.0) = 3568.00
        assertThat(result.basePrice()).isEqualByComparingTo("89.20");
        assertThat(result.minSeed()).isEqualByComparingTo("3568.00");
        assertThat(result.skipReason()).isNull();
        assertThat(result.ticker()).isEqualTo("SOXL");
        verify(pricePort, never()).getPrice(any(), any());
        verify(pricePort, never()).getPriceSnapshot(any(), any());
    }

    @Test
    void privacy_no_base_returns_skip_reason() {
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.empty());

        var result = service.strategySeedPreview(accountId, userId, StrategyType.PRIVACY, StrategyTicker.SOXL, 0);

        assertThat(result.skipReason()).isEqualTo("NO_PRIVACY_BASE");
        assertThat(result.basePrice()).isNull();
        assertThat(result.minSeed()).isNull();
    }

    @Test
    void privacy_with_base_returns_min_seed() {
        // given: 기준매매표 있음, currentCycleStart = 5000.00
        PrivacyCurrentBase base = new PrivacyCurrentBase(StrategyTicker.SOXL, new BigDecimal("5000.00"), null);
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.of(base));

        var result = service.strategySeedPreview(accountId, userId, StrategyType.PRIVACY, StrategyTicker.SOXL, 0);

        // then: PRIVACY minSeed = currentCycleStart / 2
        assertThat(result.basePrice()).isEqualByComparingTo("5000.00");
        assertThat(result.minSeed()).isEqualByComparingTo("2500.00");
        assertThat(result.skipReason()).isNull();
    }
}
