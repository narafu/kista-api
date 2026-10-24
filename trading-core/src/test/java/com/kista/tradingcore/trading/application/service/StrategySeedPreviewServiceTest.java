package com.kista.tradingcore.trading.application.service;
import com.kista.tradingcore.trading.application.service.support.StrategyHistoryQueryService;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.privacy.domain.model.PrivacyCurrentBase;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy;
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
    @Mock BrokerPricePort pricePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock MarginPort marginPort;
    @Mock StrategyPort strategyPort;
    @Mock CyclePositionPort cyclePositionPort;

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
                accountPort, strategyPort, cyclePositionPort, null, cycleStrategies, privacyTradePort, pricePort, marginPort);
        service = new StrategyService(
                null, null, null, null, null, null, null,
                accountPort, null, historyQueryService, null, null, null, null, null, null);
        // 실제 Account record — account.toBrokerRef()가 인스턴스 메서드라 mock(Account.class)로는 null 반환됨
        account = new Account(accountId, userId, "테스트계좌", "74420614-01", "key", "secret", null, Broker.KIS, null);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(account);
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
        PrivacyCurrentBase base = new PrivacyCurrentBase(StrategyTicker.SOXL, new BigDecimal("5000.00"), null, BigDecimal.ZERO, null);
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.of(base));

        var result = service.strategySeedPreview(accountId, userId, StrategyType.PRIVACY, StrategyTicker.SOXL, 0);

        // then: PRIVACY minSeed = currentCycleStart / 2
        assertThat(result.basePrice()).isEqualByComparingTo("5000.00");
        assertThat(result.minSeed()).isEqualByComparingTo("2500.00");
        assertThat(result.skipReason()).isNull();
    }

    private void stubInfinitePrice() {
        when(pricePort.getPrevClose(eq(StrategyTicker.SOXL), any(BrokerAccountRef.class)))
                .thenReturn(new BigDecimal("89.20"));
    }

    @Test
    void includesAvailableCash() {
        // 마진 5000 − 계좌 내 전략 원장 합 1200 = 3800
        stubInfinitePrice();
        Strategy existing = new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        when(marginPort.getUsdBuyableAmount(any(BrokerAccountRef.class))).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(accountId)).thenReturn(List.of(existing));
        when(cyclePositionPort.findLatestOneByStrategyId(existing.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1200"), null, null, BigDecimal.ZERO, null, null)));

        var result = service.strategySeedPreview(accountId, userId, StrategyType.INFINITE, StrategyTicker.SOXL, 20);

        assertThat(result.availableCash()).isEqualByComparingTo("3800");
    }

    @Test
    void mockAccountHasNullAvailableCash() {
        stubInfinitePrice();
        Account mock = new Account(accountId, userId, "모의", "74420614-01", "key", "secret", null, Broker.MOCK, null);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(mock);

        var result = service.strategySeedPreview(accountId, userId, StrategyType.INFINITE, StrategyTicker.SOXL, 20);

        assertThat(result.availableCash()).isNull();
        verify(marginPort, never()).getUsdBuyableAmount(any());
    }

    @Test
    void marginFailureGivesNullAvailableCash() {
        stubInfinitePrice();
        when(marginPort.getUsdBuyableAmount(any(BrokerAccountRef.class))).thenThrow(new RuntimeException("조회 실패"));

        var result = service.strategySeedPreview(accountId, userId, StrategyType.INFINITE, StrategyTicker.SOXL, 20);

        assertThat(result.availableCash()).isNull();
        assertThat(result.minSeed()).isNotNull(); // 가용 예수금 실패가 미리보기를 막지 않는다
    }

    @Test
    void cryptoAccountHasNullAvailableCashWithoutMarginCall() {
        stubInfinitePrice();
        Account crypto = new Account(accountId, userId, "업비트", null, "key", "secret", null, Broker.UPBIT, null);
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(crypto);

        var result = service.strategySeedPreview(accountId, userId, StrategyType.INFINITE, StrategyTicker.SOXL, 20);

        assertThat(result.availableCash()).isNull();
        verify(marginPort, never()).getUsdBuyableAmount(any());
    }
}
