package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyInfiniteDetail;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.domain.strategy.*;
import com.kista.tradingcore.matching.domain.strategy.*;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.support.TradingFixtures;
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
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyCycleSeedType;

// 사이클 종료 후 재등록(MAINTAIN/MAX) 정책 검증 — 최소금액 가드(InfiniteCycleOrderStrategy.MIN_DEPOSIT_MULTIPLIER=44) 포함
@ExtendWith(MockitoExtension.class)
@DisplayName("CycleRotationService 단위 테스트")
class CycleRotationServiceTest {
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

    static final TradingAccount ACCOUNT = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());

    static final TradingUserProfile USER = TradingFixtures.tradingUserProfile(ACCOUNT.userId());

    @BeforeEach
    void setUp() {
        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy)));
        service = new CycleRotationService(strategyPort, strategyVersionPort, strategyInfiniteDetailPort,
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


    private void stubLedger(Strategy strategy, StrategyCycle current, String ledger) {
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.of(
                new CyclePosition(UUID.randomUUID(), current.id(), new BigDecimal(ledger), null, null, BigDecimal.valueOf(0), null, null)));
    }

    @Test
    @DisplayName("MAINTAIN — 원장이 시드보다 크면(수익) 시드를 유지한다")
    void maintainAfterProfitKeepsStartAmount() {
        // minRequired = 22 × 44 = 968 — 시드 1000 통과
        Strategy strategy = strategy(StrategyCycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        stubLedger(strategy, current, "1200");

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, new BigDecimal("1000.00"), PRICE);
        verify(eventPublisher, never()).publishEvent(any(InsufficientBalanceEvent.class));
    }

    @Test
    @DisplayName("MAINTAIN — 원장이 시드보다 작으면(손실) 원장으로 시작한다")
    void maintainUsesMinOfStartAmountAndLedger() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        stubLedger(strategy, current, "800");

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, new BigDecimal("800"), PRICE);
    }

    @Test
    @DisplayName("MAINTAIN — 최소금액 미달이어도 축소된 시드로 재등록 진행 + 관리자 알림")
    void maintain_belowMinRequired_registersAnywayAndNotifies() {
        // minRequired = 22 × 44 = 968 — 기존 500은 미달
        BigDecimal deposit = new BigDecimal("500.00");
        Strategy strategy = strategy(StrategyCycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), deposit);
        stubLedger(strategy, current, "600");

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof InsufficientBalanceEvent ibe
                && ibe.userId() == null && ACCOUNT.id().equals(ibe.accountId())
                && ibe.usdDeposit().compareTo(deposit) == 0 && ibe.ticker() == StrategyTicker.SOXL));
        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, deposit, PRICE);
    }

    @Test
    @DisplayName("MAINTAIN — 직전 포지션이 없으면 시드로 대체한다")
    void maintain_noPosition_usesStartAmount() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAINTAIN);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        when(cyclePositionPort.findLatestOneByStrategyId(strategy.id())).thenReturn(Optional.empty());

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, new BigDecimal("1000.00"), PRICE);
    }

    @Test
    @DisplayName("MAX — 내부 원장 기준으로 재등록한다")
    void maxUsesLedger() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        stubLedger(strategy, current, "1200");

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(cycleSnapshotCreator).createCycleAndSnapshot(strategy.id(), STRATEGY_VERSION_ID, new BigDecimal("1200"), PRICE);
    }

    @Test
    @DisplayName("MAX — 원장이 0 이하면 새 사이클 없이 PAUSED하고 관리자에게 알린다")
    void max_zeroLedger_pausesWithoutCreatingCycle() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
        stubLedger(strategy, current, "0");

        service.rotate(strategy, current, ACCOUNT, USER, PRICE, null);

        verify(strategyPort).pause(strategy.id());
        verify(cycleSnapshotCreator, never()).createCycleAndSnapshot(any(), any(), any(), any());
        verify(eventPublisher).publishEvent(any(InsufficientBalanceEvent.class));
    }

    @Test
    @DisplayName("MAX — 재등록 도중 예외 발생 시 전략 PAUSED 후 재throw (좀비 사이클 방지)")
    void max_createCycleFails_pausesStrategyAndRethrows() {
        Strategy strategy = strategy(StrategyCycleSeedType.MAX);
        StrategyCycle current = currentCycle(strategy.id(), new BigDecimal("1000.00"));
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
        doThrow(new RuntimeException("리스너 실패")).when(eventPublisher).publishEvent(any(Object.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.rotate(strategy, current, ACCOUNT, USER, PRICE, null))
                .hasMessage("리스너 실패");

        verify(cycleSnapshotCreator).createCycleAndSnapshot(eq(strategy.id()), eq(STRATEGY_VERSION_ID), any(), eq(PRICE));
        verify(strategyPort, never()).pause(any());
    }
}
