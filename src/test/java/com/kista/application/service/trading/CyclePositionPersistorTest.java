package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.*;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// CyclePositionPersistor 단위 테스트:
// - VR holdings=0 → markEnded 미호출 + rollIfDue 호출
// - INFINITE holdings=0 기존 동작 무회귀
// - VR 포지션 저장 후 rollIfDue 항상 호출
@ExtendWith(MockitoExtension.class)
@DisplayName("CyclePositionPersistor 단위 테스트")
class CyclePositionPersistorTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CycleRotationService cycleRotationService;
    @Mock UserNotificationPort userNotificationPort;
    @Mock VrCycleRolloverService vrCycleRolloverService;

    CyclePositionPersistor persistor;

    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();
    static final UUID STRATEGY_ID = UUID.randomUUID();
    static final UUID CYCLE_ID = UUID.randomUUID();
    static final UUID VERSION_ID = UUID.randomUUID();
    static final LocalDate TODAY = LocalDate.of(2026, 6, 29);
    static final BigDecimal PRICE = new BigDecimal("50.00");

    static final Account ACCOUNT = new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
            "74420614", "key", "secret", null, Account.Broker.KIS, null);

    static final User USER = new User(USER_ID, "kakao-1", "홍길동",
            User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.TELEGRAM);

    private StrategyCycle cycle(UUID strategyId) {
        return new StrategyCycle(CYCLE_ID, strategyId, VERSION_ID,
                new BigDecimal("1000.00"), null,
                TODAY.minusWeeks(4), null,
                Instant.now(), null);
    }

    private Strategy vrStrategy() {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.MAINTAIN);
    }

    private Strategy infiniteStrategy() {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAINTAIN);
    }

    @BeforeEach
    void setUp() {
        // CycleOrderStrategies 실제 객체 조립 — VrCycleOrderStrategy(capability: endsCycleOnLiquidation=false)
        VrStrategy vrStrategy = mock(VrStrategy.class);
        VrCycleOrderStrategy vrOrderStrategy = new VrCycleOrderStrategy(vrStrategy);
        InfiniteStrategy infStrategy = mock(InfiniteStrategy.class);
        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        InfiniteCycleOrderStrategy infiniteOrderStrategy = new InfiniteCycleOrderStrategy(infStrategy, reverseStrategy);
        CycleOrderStrategies cycleOrderStrategies = new CycleOrderStrategies(
                List.of(vrOrderStrategy, infiniteOrderStrategy));

        persistor = new CyclePositionPersistor(
                cyclePositionPort, cyclePositionInfiniteDetailPort, strategyInfiniteDetailPort,
                strategyCyclePort, cycleRotationService, userNotificationPort,
                cycleOrderStrategies, vrCycleRolloverService);
    }

    // --- VR 전략 테스트 ---

    @Test
    @DisplayName("VR holdings=0 — markEnded 미호출, rollIfDue 호출")
    void vr_holdingsZero_noCycleEnd_rollIfDueCalled() {
        Strategy strategy = vrStrategy();
        StrategyCycle cycle = cycle(strategy.id());
        // 이전 포지션: holdings=5 (prevHadHoldings=true)
        CyclePosition prevPos = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000.00"), PRICE, new BigDecimal("45.00"), 5, Instant.now(), null);
        when(cyclePositionPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of(prevPos));
        // 포지션 저장 stub
        CyclePosition savedPos = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1050.00"), PRICE, null, 0, Instant.now(), null);
        when(cyclePositionPort.save(any())).thenReturn(savedPos);

        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1050.00"));
        BatchContext batchCtx = new BatchContext(strategy, cycle, ACCOUNT, USER);

        persistor.saveCyclePosition(TODAY, balance, batchCtx, PRICE, null);

        // VR은 holdings=0에도 사이클 종료 발동 안 됨
        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleRotationService, never()).rotate(any(), any(), any(), any(), any(), any());
        // rollIfDue는 항상 호출됨
        verify(vrCycleRolloverService).rollIfDue(batchCtx, balance, PRICE, TODAY);
    }

    @Test
    @DisplayName("VR holdings>0 — rollIfDue 호출, markEnded 미호출")
    void vr_holdingsPositive_rollIfDueCalled_noMarkEnded() {
        Strategy strategy = vrStrategy();
        StrategyCycle cycle = cycle(strategy.id());
        CyclePosition savedPos = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000.00"), PRICE, new BigDecimal("45.00"), 10, Instant.now(), null);
        when(cyclePositionPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of(savedPos));
        when(cyclePositionPort.save(any())).thenReturn(savedPos);

        AccountBalance balance = new AccountBalance(10, new BigDecimal("45.00"), new BigDecimal("1000.00"));
        BatchContext batchCtx = new BatchContext(strategy, cycle, ACCOUNT, USER);

        persistor.saveCyclePosition(TODAY, balance, batchCtx, PRICE, null);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(vrCycleRolloverService).rollIfDue(batchCtx, balance, PRICE, TODAY);
    }

    // --- INFINITE 전략 무회귀 테스트 ---

    @Test
    @DisplayName("INFINITE holdings=0 + prevHadHoldings=true — markEnded + rotate 호출 (기존 동작 무회귀)")
    void infinite_holdingsZero_prevHadHoldings_marksEndedAndRotates() {
        Strategy strategy = infiniteStrategy();
        StrategyCycle cycle = cycle(strategy.id());
        // 이전 포지션: holdings=5
        CyclePosition prevPos = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000.00"), PRICE, new BigDecimal("45.00"), 5, Instant.now(), null);
        when(cyclePositionPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of(prevPos));
        // INFINITE 리버스모드 — empty 반환으로 기본값 사용
        when(cyclePositionInfiniteDetailPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of());
        when(strategyInfiniteDetailPort.findByStrategyVersionId(VERSION_ID))
                .thenReturn(java.util.Optional.empty());

        CyclePosition savedPos = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1050.00"), PRICE, null, 0, Instant.now(), null);
        when(cyclePositionPort.save(any())).thenReturn(savedPos);

        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1050.00"));
        BatchContext batchCtx = new BatchContext(strategy, cycle, ACCOUNT, USER);

        persistor.saveCyclePosition(TODAY, balance, batchCtx, PRICE, null);

        // INFINITE: markEnded + rotate 호출
        verify(strategyCyclePort).markEnded(CYCLE_ID, balance.usdDeposit(), TODAY);
        verify(cycleRotationService).rotate(strategy, cycle, ACCOUNT, USER, PRICE, null);
        // INFINITE가 아닌 VR이 아니므로 vrCycleRolloverService 미호출
        verifyNoInteractions(vrCycleRolloverService);
    }

    @Test
    @DisplayName("INFINITE holdings=0 + prevHadHoldings=false (0회차 매수 실패) — markEnded 미호출")
    void infinite_holdingsZero_noPrevHoldings_noMarkEnded() {
        Strategy strategy = infiniteStrategy();
        StrategyCycle cycle = cycle(strategy.id());
        // 이전 포지션 없음 (첫 실행 실패)
        when(cyclePositionPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of());
        when(cyclePositionInfiniteDetailPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of());
        when(strategyInfiniteDetailPort.findByStrategyVersionId(VERSION_ID))
                .thenReturn(java.util.Optional.empty());

        CyclePosition savedPos = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000.00"), PRICE, null, 0, Instant.now(), null);
        when(cyclePositionPort.save(any())).thenReturn(savedPos);

        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000.00"));
        BatchContext batchCtx = new BatchContext(strategy, cycle, ACCOUNT, USER);

        persistor.saveCyclePosition(TODAY, balance, batchCtx, PRICE, null);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleRotationService, never()).rotate(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(vrCycleRolloverService);
    }
}
