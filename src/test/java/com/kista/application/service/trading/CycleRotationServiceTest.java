package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyInfiniteDetail;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.strategy.*;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 사이클 종료 후 재등록(MAINTAIN/MAX) 정책 검증 — 최소금액 가드(InfiniteCycleOrderStrategy.MIN_DEPOSIT_MULTIPLIER=44) 포함
@ExtendWith(MockitoExtension.class)
@DisplayName("CycleRotationService 단위 테스트")
class CycleRotationServiceTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock MarginPort marginPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock CyclePositionPort cyclePositionPort;           // MAX 시드 계산용 최신 포지션 조회 (읽기 전용)
    @Mock CycleSnapshotCreator cycleSnapshotCreator;    // StrategyCycle + CyclePosition 원자적 저장
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock InfiniteStrategy infiniteStrategy;
    @Mock PrivacyStrategy privacyStrategy;

    CycleRotationService service;

    static final BigDecimal PRICE = new BigDecimal("22.00");
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    static final User USER = DomainFixtures.activeUserWithTelegram(ACCOUNT.userId());

    @BeforeEach
    void setUp() {
        // userSettingsPort — 잔고검증 기본값(ON) 반환: 증권사 실잔고 경로로 진행
        when(userSettingsPort.findOrDefault(any()))
                .thenReturn(UserSettings.defaultFor(USER.id()));

        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        service = new CycleRotationService(registry, strategyPort, strategyVersionPort, strategyInfiniteDetailPort,
                cyclePositionPort, cycleSnapshotCreator, notifyPort, userNotificationPort, cycleStrategies, userSettingsPort);
        lenient().when(strategyVersionPort.findActiveByStrategyId(any()))
                .thenReturn(Optional.of(new StrategyVersion(STRATEGY_VERSION_ID, null, 1, null, null)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(any()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 20)));
    }

    // StrategyCycle — 현재 사이클 (MAINTAIN/MAX 시드 계산 기준)
    private StrategyCycle currentCycle(UUID strategyId, BigDecimal startAmount) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, startAmount,
                null, LocalDate.now(), null, Instant.now(), null);
    }

    private Strategy strategy(Strategy.CycleSeedType seedType) {
        return new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, seedType);
    }


    @Test
    @DisplayName("MAINTAIN — 기존 initialUsdDeposit 유지하여 재등록")
    void maintain_keepsExistingDeposit() {
        // minRequired = 22 × 44 = 968 — 기존 1000 통과
        BigDecimal deposit = new BigDecimal("1000.00");
        Strategy strategy = strategy(Strategy.CycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), deposit);
        // MAINTAIN도 실잔고 확인 — actual >= maintainSeed 이면 재등록
        when(registry.require(ACCOUNT, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(new BigDecimal("1500.00"));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        // StrategyCycle + CyclePosition 원자적 저장 위임 검증
        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, deposit, PRICE);
        verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());
    }

    @Test
    @DisplayName("MAINTAIN — 최소금액 미달 시 재등록 취소 + 잔고부족 알림")
    void maintain_belowMinRequired_cancelsAndNotifies() {
        // minRequired = 22 × 44 = 968 — 기존 500은 미달
        // actual(600) >= maintainSeed(500) → targetSeed=500, 하지만 500 < minRequired(968) → 잔고부족 알림
        BigDecimal deposit = new BigDecimal("500.00");
        Strategy strategy = strategy(Strategy.CycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), deposit);
        when(registry.require(ACCOUNT, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(new BigDecimal("600.00"));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT),
                argThat(b -> b.usdDeposit().compareTo(deposit) == 0), eq(Ticker.SOXL));
        verify(cycleSnapshotCreator, never()).createCycleAndSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — 내부 원장 maxSeed 기준으로 재등록 (KIS 잔고는 검증용)")
    void max_resolvesDepositFromKisMargin() {
        // maxSeed = 마지막 CyclePosition.usdDeposit = 1500 (내부 원장)
        // KIS actual(2000) >= maxSeed(1500) → targetSeed = 1500 (NOT 2000)
        BigDecimal maintainDeposit = new BigDecimal("1000.00");
        BigDecimal maxSeedDeposit = new BigDecimal("1500.00");
        Strategy strategy = strategy(Strategy.CycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), maintainDeposit);

        // 마지막 CyclePosition이 있어야 maxSeed가 currentCycle.initialUsdDeposit fallback이 아닌 실제 값 사용
        CyclePosition lastPosition = new CyclePosition(UUID.randomUUID(), current.id(), maxSeedDeposit, null, null, 0, null, null);

        when(registry.require(ACCOUNT, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(new BigDecimal("2000.00"));
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.of(lastPosition));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        // KIS 잔고(2000) >= maxSeed(1500) → maxSeedDeposit으로 재등록
        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, maxSeedDeposit, PRICE);
    }

    @Test
    @DisplayName("MAX — KIS 잔고 조회 실패 시 재등록 중단 + 관리자 오류 알림")
    void max_kisLookupFails_abortsAndNotifiesError() {
        Strategy strategy = strategy(Strategy.CycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        RuntimeException kisError = new RuntimeException("KIS 잔고 조회 실패");
        when(registry.require(ACCOUNT, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT)).thenThrow(kisError);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyError(kisError);
        verify(cycleSnapshotCreator, never()).createCycleAndSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — USD 잔고 행이 없으면 재등록 중단 + 오류 알림")
    void max_noUsdMarginRow_abortsAndNotifiesError() {
        Strategy strategy = strategy(Strategy.CycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        // USD 잔고 없음 → router가 BigDecimal.ZERO 반환
        when(registry.require(ACCOUNT, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT)).thenReturn(BigDecimal.ZERO);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(cycleSnapshotCreator, never()).createCycleAndSnapshot(any(), any(), any(), any());
    }

}
