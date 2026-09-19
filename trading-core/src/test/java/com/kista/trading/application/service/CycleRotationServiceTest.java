package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyInfiniteDetail;
import com.kista.trading.domain.model.StrategyVersion;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.application.port.output.*;
import com.kista.broker.application.port.output.MarginPort;
import com.kista.trading.domain.strategy.*;
import com.kista.matching.domain.strategy.*;
import com.kista.sharedkernel.InsufficientBalanceEvent;
import com.kista.sharedkernel.TradingErrorEvent;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyCycleSeedType;

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
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock InfiniteStrategy infiniteStrategy;
    @Mock PrivacyStrategy privacyStrategy;

    CycleRotationService service;

    static final BigDecimal PRICE = new BigDecimal("22.00");
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    static final BrokerAccountRef ACCOUNT_REF = ACCOUNT.toBrokerRef();

    // 잔고검증 ON(기본값) — 증권사 실잔고 경로로 진행
    static final TradingUserProfile USER = DomainFixtures.tradingUserProfile(ACCOUNT.userId());

    @BeforeEach
    void setUp() {
        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        service = new CycleRotationService(registry, strategyPort, strategyVersionPort, strategyInfiniteDetailPort,
                cyclePositionPort, cycleSnapshotCreator, eventPublisher, cycleStrategies);
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

    private Strategy strategy(StrategyCycleSeedType seedType) {
        return new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, seedType);
    }


    @Test
    @DisplayName("MAINTAIN — 기존 initialUsdDeposit 유지하여 재등록")
    void maintain_keepsExistingDeposit() {
        // minRequired = 22 × 44 = 968 — 기존 1000 통과
        BigDecimal deposit = new BigDecimal("1000.00");
        Strategy strategy = strategy(StrategyCycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), deposit);
        // MAINTAIN도 실잔고 확인 — actual >= maintainSeed 이면 재등록
        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenReturn(new BigDecimal("1500.00"));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        // StrategyCycle + CyclePosition 원자적 저장 위임 검증
        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, deposit, PRICE);
        verify(eventPublisher, never()).publishEvent(any(InsufficientBalanceEvent.class));
    }

    @Test
    @DisplayName("MAINTAIN — 최소금액 미달이어도 축소된 시드로 재등록 진행 + 잔고부족 알림")
    void maintain_belowMinRequired_registersAnywayAndNotifies() {
        // minRequired = 22 × 44 = 968 — 기존 500은 미달
        // actual(600) >= maintainSeed(500) → targetSeed=500, 500 < minRequired(968)이어도 재등록은 차단하지 않음
        BigDecimal deposit = new BigDecimal("500.00");
        Strategy strategy = strategy(StrategyCycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), deposit);
        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenReturn(new BigDecimal("600.00"));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof InsufficientBalanceEvent ibe
                && ACCOUNT.id().equals(ibe.accountId()) && ibe.usdDeposit().compareTo(deposit) == 0 && ibe.ticker() == StrategyTicker.SOXL));
        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, deposit, PRICE);
    }

    @Test
    @DisplayName("MAX — 내부 원장 maxSeed 기준으로 재등록 (KIS 잔고는 검증용)")
    void max_resolvesDepositFromKisMargin() {
        // maxSeed = 마지막 CyclePosition.usdDeposit = 1500 (내부 원장)
        // KIS actual(2000) >= maxSeed(1500) → targetSeed = 1500 (NOT 2000)
        BigDecimal maintainDeposit = new BigDecimal("1000.00");
        BigDecimal maxSeedDeposit = new BigDecimal("1500.00");
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), maintainDeposit);

        // 마지막 CyclePosition이 있어야 maxSeed가 currentCycle.initialUsdDeposit fallback이 아닌 실제 값 사용
        CyclePosition lastPosition = new CyclePosition(UUID.randomUUID(), current.id(), maxSeedDeposit, null, null, 0, null, null);

        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenReturn(new BigDecimal("2000.00"));
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.of(lastPosition));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        // KIS 잔고(2000) >= maxSeed(1500) → maxSeedDeposit으로 재등록
        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, maxSeedDeposit, PRICE);
    }

    @Test
    @DisplayName("MAX — KIS 잔고 조회 실패 시 재등록 중단 + 관리자 오류 알림")
    void max_kisLookupFails_abortsAndNotifiesError() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        RuntimeException kisError = new RuntimeException("KIS 잔고 조회 실패");
        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenThrow(kisError);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null && tee.message().equals(kisError.getMessage())));
        verify(cycleSnapshotCreator, never()).createCycleAndSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — USD 잔고 행이 없으면 재등록 중단 + 오류 알림")
    void max_noUsdMarginRow_abortsAndNotifiesError() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        // USD 잔고 없음 → router가 BigDecimal.ZERO 반환
        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenReturn(BigDecimal.ZERO);

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
        verify(cycleSnapshotCreator, never()).createCycleAndSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAX — 증권사 잔고 조회 실패 시 전략 PAUSED (좀비 사이클 방지)")
    void max_kisLookupFails_pausesStrategy() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenThrow(new RuntimeException("KIS 잔고 조회 실패"));

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(strategyPort).pause(strategy.id());
    }

    @Test
    @DisplayName("MAX — 재등록 도중 예외 발생 시 전략 PAUSED 후 재throw (좀비 사이클 방지)")
    void max_createCycleFails_pausesStrategyAndRethrows() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenReturn(new BigDecimal("2000.00"));
        RuntimeException boom = new RuntimeException("사이클 생성 실패");
        when(cycleSnapshotCreator.createCycleAndSnapshot(eq(strategy.id()), eq(STRATEGY_VERSION_ID), any(), eq(PRICE)))
                .thenThrow(boom);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.rotate(strategy, current, ACCOUNT, USER, PRICE, null))
                .isSameAs(boom);

        verify(strategyPort).pause(strategy.id());
    }

    @Test
    @DisplayName("MAX — 사이클 생성 성공 뒤 알림 리스너가 예외를 던져도 전략은 PAUSED되지 않는다")
    void max_listenerFailsAfterCycleCreated_doesNotPause() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        when(registry.require(ACCOUNT_REF, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(ACCOUNT_REF)).thenReturn(new BigDecimal("2000.00"));
        doThrow(new RuntimeException("리스너 실패")).when(eventPublisher).publishEvent(any(Object.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.rotate(strategy, current, ACCOUNT, USER, PRICE, null))
                .hasMessage("리스너 실패");

        verify(cycleSnapshotCreator).createCycleAndSnapshot(eq(strategy.id()), eq(STRATEGY_VERSION_ID), any(), eq(PRICE));
        verify(strategyPort, never()).pause(any());
    }
}
