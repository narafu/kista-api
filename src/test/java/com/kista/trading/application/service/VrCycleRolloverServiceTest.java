package com.kista.trading.application.service;

import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.StrategyRef; import com.kista.trading.domain.model.*;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.user.domain.model.User;
import com.kista.trading.application.port.output.*;
import com.kista.market.application.port.output.MarketCalendarPort;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyCycleSeedType;

// VR N주 사이클 롤오버 단위 테스트
// due 판정·V′ 계산·markEnded·새 사이클 생성·각종 skip 조건 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("VrCycleRolloverService 단위 테스트")
class VrCycleRolloverServiceTest {

    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CycleSnapshotCreator cycleSnapshotCreator;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MarketCalendarPort marketCalendarPort;
    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerPricePort brokerPricePort;

    VrCycleRolloverService service;

    // 고정 UUID 상수
    static final UUID STRATEGY_ID = UUID.randomUUID();
    static final UUID CYCLE_ID = UUID.randomUUID();
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();
    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    static final BigDecimal CLOSING_PRICE = new BigDecimal("50.00");
    static final BigDecimal USD_DEPOSIT = new BigDecimal("1000.00");
    // 10주 보유, 평단가=45.00
    static final AccountBalance POST_BALANCE = new AccountBalance(10, new BigDecimal("45.00"), USD_DEPOSIT);

    static final Account ACCOUNT = DomainFixtures.kisAccount(ACCOUNT_ID, USER_ID);
    static final BrokerAccountRef ACCOUNT_REF = ACCOUNT.toBrokerRef();

    static final User USER = DomainFixtures.activeUserWithTelegram(USER_ID);

    // 테스트 기준일: 2026-06-01 (기준, 실제 today는 각 테스트에서 지정)
    static final LocalDate CYCLE_START = LocalDate.of(2026, 6, 1);

    // 현재 사이클: startDate = CYCLE_START
    static final StrategyCycle CYCLE = new StrategyCycle(
            CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID,
            USD_DEPOSIT, null,
            CYCLE_START, null,
            Instant.now(), null);

    static final StrategyRef VR_STRATEGY = new StrategyRef(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.VR,
            StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.MAINTAIN);

    // 4주 주기, bandWidth=15, recurringAmount=0 → gradient=10, poolLimitRate=0.50
    // 램프 미개입(gMax=initialGradient, poolLimitFloor=initialPoolLimitRate) — 기존 "고정값" 테스트 전제 유지
    static final StrategyVrDetail DETAIL = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
            10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));

    // 사이클 VR 상세: value=55.00, gradient=10, poolLimit=500
    static final StrategyCycleVrDetail CYCLE_VR = new StrategyCycleVrDetail(
            CYCLE_ID, new BigDecimal("55.00"), 10, new BigDecimal("500.00"));

    BatchContext ctx;

    @BeforeEach
    void setUp() {
        service = new VrCycleRolloverService(
                strategyCycleVrPort, strategyVrDetailPort, strategyCyclePort,
                cycleSnapshotCreator, eventPublisher,
                marketCalendarPort, registry);
        ctx = new BatchContext(VR_STRATEGY, CYCLE, ACCOUNT, USER);
        // 램프 재계산 기준 — 최초 사이클 시작일 조회 기본 stub (CYCLE 자신이 최초 사이클인 케이스)
        // due 미도래·cycleVr 미존재 등 조기 return 테스트는 호출 자체가 없어 unnecessary stub 문제 없음(lenient)
        lenient().when(strategyCyclePort.findFirstByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        // due일 확정 종가 조회 — 기본은 항상 거래일(휴장 역탐색 불필요) + CLOSING_PRICE 반환
        lenient().when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        lenient().when(registry.require(any(BrokerAccountRef.class), eq(BrokerPricePort.class))).thenReturn(brokerPricePort);
        lenient().when(brokerPricePort.getClosingPrice(any(), any(), any())).thenReturn(CLOSING_PRICE);
    }

    @Test
    @DisplayName("due 미도래 — 조용히 return, markEnded 미호출")
    void notDue_returnsQuietly() {
        // dueDate = 2026-06-01 + 4주 = 2026-06-29. today = 2026-06-28 → 미도래
        LocalDate today = CYCLE_START.plusWeeks(4).minusDays(1);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleSnapshotCreator, never()).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("due 당일 도래 — 기존 자산 그대로 종료하고 새 사이클로 이월")
    void due_onDueDate_rollsOver() {
        // dueDate = CYCLE_START + 4주 = 당일
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // 기존 자산 = 예수금 1000 + 보유 10주 x 종가 50 = 1500
        verify(strategyCyclePort).markEnded(CYCLE_ID, new BigDecimal("1500.00"), today);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                eq(STRATEGY_ID), eq(STRATEGY_VERSION_ID),
                eq(POST_BALANCE), eq(CLOSING_PRICE),
                any(BigDecimal.class), eq(10), any(BigDecimal.class), eq(today));
    }

    @Test
    @DisplayName("due 지난 후 도래 — markEnded + 새 사이클 생성은 due일 기준(배치 실행일 아님, 지연 이월)")
    void due_afterDueDate_rollsOver() {
        // 휴장일로 밀린 케이스: today = dueDate + 1일
        LocalDate dueDate = CYCLE_START.plusWeeks(4);
        LocalDate today = dueDate.plusDays(1);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, dueDate, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // 종료일/신규 시작일 모두 due일 그대로 — 배치가 밀려 실행된 today가 아님 (스케쥴 앵커 고정)
        verify(strategyCyclePort).markEnded(CYCLE_ID, new BigDecimal("1500.00"), dueDate);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), anyInt(), any(), eq(dueDate));
    }

    @Test
    @DisplayName("due일이 휴장(주말)이면 종료일/시작일/평가종가 모두 그 직전 거래일 기준 — due일 자체가 아님")
    void dueDateOnHoliday_anchorsToLastTradingDay() {
        // CYCLE_START(2026-06-01)는 월요일 — +2주(due=06-15, 월요일)로 만들고, due일 자체를 휴장으로 만들어
        // 직전 거래일(06-12, 금요일)로 역탐색되는지 검증한다
        StrategyVrDetail twoWeekDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 2, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        LocalDate dueDate = CYCLE_START.plusWeeks(2); // 2026-06-15
        LocalDate lastTradingDay = dueDate.minusDays(3); // 2026-06-12 (금)
        LocalDate today = dueDate; // 배치는 due일 당일에 정상 실행됐다고 가정 — due일 자체가 휴장인 케이스

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(twoWeekDetail));
        // due일과 그 사이 이틀은 휴장, 그 이전(lastTradingDay)부터는 거래일
        when(marketCalendarPort.isMarketOpen(dueDate)).thenReturn(false);
        when(marketCalendarPort.isMarketOpen(dueDate.minusDays(1))).thenReturn(false);
        when(marketCalendarPort.isMarketOpen(dueDate.minusDays(2))).thenReturn(false);
        when(marketCalendarPort.isMarketOpen(lastTradingDay)).thenReturn(true);
        BigDecimal holidayEvalPrice = new BigDecimal("60.00");
        when(brokerPricePort.getClosingPrice(StrategyTicker.TQQQ, lastTradingDay, ACCOUNT_REF)).thenReturn(holidayEvalPrice);
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, lastTradingDay, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // 평가금 = holdings(10) × 직전 거래일 종가(60.00) = 600.00 — due일 자체가 아닌 직전 거래일 종가로 계산됨
        verify(brokerPricePort).getClosingPrice(StrategyTicker.TQQQ, lastTradingDay, ACCOUNT_REF);
        verify(strategyCyclePort).markEnded(eq(CYCLE_ID), eq(new BigDecimal("1600.00")), eq(lastTradingDay));
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), eq(holidayEvalPrice), any(), anyInt(), any(), eq(lastTradingDay));
    }

    @Test
    @DisplayName("V′ 계산값 검증 — gradient=10, recurringAmount=0, evaluation=holdings×closingPrice")
    void newValue_calculatedCorrectly() {
        // V′ = V + pool/G + recurring + (evaluation − V)/(2√G)
        // = 55 + 1000/10 + 0 + (10×50 − 55)/(2×√10)
        // = 55 + 100 + 0 + (500 − 55)/(2×3.16227...)
        // = 55 + 100 + 445/6.32455... ≈ 55 + 100 + 70.39... ≈ 225.39...
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // newValue 캡처 후 범위 검증 (소수점 반올림 허용 ±0.01)
        ArgumentCaptor<BigDecimal> newValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), newValueCaptor.capture(), anyInt(), any(), any());
        BigDecimal expectedNewValue = VrPosition.nextValue(
                CYCLE_VR.value(), USD_DEPOSIT, CYCLE_VR.gradient(), DETAIL.recurringAmount(),
                BigDecimal.valueOf(POST_BALANCE.holdings()).multiply(CLOSING_PRICE));
        assertThat(newValueCaptor.getValue()).isEqualByComparingTo(expectedNewValue);
    }

    @Test
    @DisplayName("gradient 이월 — cycleVr.gradient() 값이 새 사이클에 그대로 전달")
    void gradient_passedThrough() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        // gradient=10 (cycleVr 고정값)
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        ArgumentCaptor<Integer> gradientCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), gradientCaptor.capture(), any(), any());
        assertThat(gradientCaptor.getValue()).isEqualTo(CYCLE_VR.gradient()); // 10 이월
    }

    @Test
    @DisplayName("poolLimitRate 전달 — 새 사이클에 detail.poolLimitRateAt(weeks) 값이 그대로 전달된다 (달러 파생은 조회 시점으로 이동)")
    void poolLimitRate_passedThroughToNewCycle() {
        // DETAIL.recurringAmount=0 → poolLimitRate=0.50, 램프 미개입(gMax=initial, floor=initial)이라 weeks=4에도 그대로 0.50
        // poolLimit(달러) 파생은 더 이상 이 서비스가 계산하지 않음 — CycleOrderComputer/buildSummary가 개장 pool×poolLimitRate로 조회 시점에 파생
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        ArgumentCaptor<BigDecimal> poolLimitRateCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), anyInt(), poolLimitRateCaptor.capture(), any());
        assertThat(poolLimitRateCaptor.getValue()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    @DisplayName("V′ ≤ 0 — 롤오버 보류, markEnded 미호출, 관리자+사용자 알림")
    void negativeNewValue_abortsRollover() {
        // evaluation=0 (holdings=0), pool=1, gradient=10, recurring=-9999 → V′ 음수 유도
        AccountBalance zeroHoldingsBalance = new AccountBalance(0, null, new BigDecimal("1.00"));
        // V=55, pool=1, G=10, recurring=-9999, evaluation=0
        // V′ = 55 + 1/10 + (-9999) + (0-55)/(2×√10) ≈ 55 + 0.1 - 9999 + ... 음수
        StrategyCycleVrDetail negVrDetail = new StrategyCycleVrDetail(
                CYCLE_ID, new BigDecimal("55.00"), 10, new BigDecimal("500.00"));
        // recurringAmount=-9999 → gradient=20, poolLimitRate=0.25 (램프 미개입 고정값)
        StrategyVrDetail negDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), -9999,
                20, 52, 26, 20, new BigDecimal("0.25"), 52, 26, new BigDecimal("0.25"));

        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(negVrDetail));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(negDetail));

        service.rollIfDue(ctx, zeroHoldingsBalance, CLOSING_PRICE, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleSnapshotCreator, never()).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any());
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null)); // 관리자 알림
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && USER.id().equals(tee.userId()))); // 사용자 알림
    }

    @Test
    @DisplayName("V=0, holdings=0인 채로 due 도래해도 nextValue 공식대로 V가 자연 성장한다 (강제 0 가드 제거)")
    void rollIfDue_valueZeroHoldingsZero_newValueGrowsNaturally() {
        StrategyCycleVrDetail zeroCycleVr = new StrategyCycleVrDetail(
                CYCLE_ID, BigDecimal.ZERO, 10, BigDecimal.ZERO);
        StrategyVrDetail depositDetail = new StrategyVrDetail(
                STRATEGY_VERSION_ID, 2, new BigDecimal("15.00"), 200,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));
        AccountBalance postBalance = new AccountBalance(0, null, BigDecimal.ZERO);
        LocalDate today = CYCLE_START.plusWeeks(2);

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(zeroCycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(depositDetail));

        service.rollIfDue(ctx, postBalance, CLOSING_PRICE, today);

        // markEnded는 적립 반영 전(pre-injection) 총자산 기준 — evaluation=0, pool=0 → 0.00
        verify(strategyCyclePort).markEnded(eq(CYCLE_ID), eq(new BigDecimal("0.00")), eq(today));
        // V′ 공식은 원래 pool(0, 적립 반영 전) 사용 — recurringAmount는 별도 항으로만 더해짐
        // nextValue(0, pool=0, G=10, recurring=200, eval=0) = 0 + 0/10 + 200 + (0-0)/(2√10) = 200.00
        // 다음 사이클 실 현금(pool)만 adjustedPool = pool(0) + recurringAmount(200) = 200으로 승계
        AccountBalance expectedNewCycleBalance = new AccountBalance(0, null, new BigDecimal("200.00"));
        // poolLimitRate는 달러 파생 없이 그대로 전달 — depositDetail은 램프 미개입(gMax/floor=initial)이라 initialPoolLimitRate(0.75) 그대로
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                eq(STRATEGY_ID), eq(STRATEGY_VERSION_ID), eq(expectedNewCycleBalance), eq(CLOSING_PRICE),
                eq(new BigDecimal("200.00")), eq(10), eq(new BigDecimal("0.75")), eq(today));
        verify(eventPublisher, never()).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && USER.id().equals(tee.userId())));
    }

    @Test
    @DisplayName("인출식 롤오버 — 예수금에서 recurringAmount(음수)만큼 실제 차감된 pool로 새 사이클 개장")
    void rollIfDue_withdrawal_deductsFromPool() {
        // pool=1000, recurringAmount=-100 → adjustedPool=900
        StrategyVrDetail withdrawDetail = new StrategyVrDetail(
                STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), -100,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        LocalDate today = CYCLE_START.plusWeeks(4);

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(withdrawDetail));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // markEnded는 인출 반영 전 pool(1000) 기준 총자산 그대로
        verify(strategyCyclePort).markEnded(CYCLE_ID, new BigDecimal("1500.00"), today);
        ArgumentCaptor<AccountBalance> balanceCaptor = ArgumentCaptor.forClass(AccountBalance.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), balanceCaptor.capture(), any(), any(), anyInt(), any(), any());
        assertThat(balanceCaptor.getValue().usdDeposit()).isEqualByComparingTo(new BigDecimal("900.00"));
    }

    @Test
    @DisplayName("인출식이 예수금을 초과 — pool 음수 전환 시 롤오버 보류, 관리자+사용자 알림")
    void rollIfDue_withdrawalExceedsPool_abortsRollover() {
        // pool=1000, recurringAmount=-2000 → adjustedPool=-1000 (음수) → 롤오버 보류
        StrategyVrDetail overWithdrawDetail = new StrategyVrDetail(
                STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), -2000,
                20, 52, 26, 20, new BigDecimal("0.25"), 52, 26, new BigDecimal("0.25"));
        LocalDate today = CYCLE_START.plusWeeks(4);

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(overWithdrawDetail));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleSnapshotCreator, never()).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any());
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && USER.id().equals(tee.userId())));
    }

    @Test
    @DisplayName("closingPrice null — 롤오버 skip, markEnded 미호출, 관리자 알림")
    void nullClosingPrice_skipWithNotify() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));

        service.rollIfDue(ctx, POST_BALANCE, null, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(cycleSnapshotCreator, never()).createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any());
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
    }

    @Test
    @DisplayName("cycleVr 미존재 — notifyError 후 return, markEnded 미호출")
    void cycleVrNotFound_notifiesAndReturns() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.empty());
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(strategyCyclePort, never()).markEnded(any(), any(), any());
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
    }

    @Test
    @DisplayName("holdings 승계 — 새 사이클에 postBalance(holdings=10, avgPrice=45) 그대로 전달")
    void holdingsInherited_inNewCycle() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        // postBalance가 새 사이클 스냅샷에 그대로 전달됨 확인
        ArgumentCaptor<AccountBalance> balanceCaptor = ArgumentCaptor.forClass(AccountBalance.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), balanceCaptor.capture(), any(), any(), anyInt(), any(), any());
        AccountBalance captured = balanceCaptor.getValue();
        assertThat(captured.holdings()).isEqualTo(10);
        assertThat(captured.avgPrice()).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(captured.usdDeposit()).isEqualByComparingTo(USD_DEPOSIT);
    }

    @Test
    @DisplayName("롤오버 완료 후 사용자 새 사이클 알림 발송")
    void afterRollover_notifiesUser() {
        LocalDate today = CYCLE_START.plusWeeks(4);
        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(DETAIL));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        verify(eventPublisher).publishEvent(new NewCycleStartedEvent(USER.id(), ACCOUNT.id(), VR_STRATEGY, USD_DEPOSIT));
    }

    // ── 경과주수 기반 램프 재계산 검증 (신규 기능 핵심 회귀) ──────────────────────────

    @Test
    @DisplayName("gradient 램프 재계산 — 유예기간을 넘긴 경과주수면 스냅샷(cycleVr.gradient())이 아닌 detail.gradientAt(weeks) 사용")
    void gradient_recalculatedFromRamp_notSnapshot() {
        // 최초 사이클 시작일을 훨씬 과거로 설정 — 유예기간(52주)을 넘긴 경과주수를 만든다 (스냅샷 이월이면 gradient=10 그대로일 것)
        LocalDate firstStart = CYCLE_START.minusWeeks(150);
        StrategyCycle firstCycle = new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                new BigDecimal("500.00"), null, firstStart, null, Instant.now(), null);
        // 램프 활성 detail: initialGradient=10, 52주 유예 후 26주마다 +1, 상한 20
        StrategyVrDetail rampingDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 20, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        LocalDate today = CYCLE_START.plusWeeks(4); // 현재 사이클(CYCLE)의 due는 그대로 충족

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR)); // 스냅샷 gradient=10
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(rampingDetail));
        when(strategyCyclePort.findFirstByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(firstCycle));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        long expectedWeeks = ChronoUnit.WEEKS.between(firstStart, today);
        int expectedGradient = rampingDetail.gradientAt(expectedWeeks);
        // 테스트 픽스처 유효성 자체 검증 — 실제로 유예기간을 넘겨 상승했는지 확인
        assertThat(expectedGradient).isGreaterThan(CYCLE_VR.gradient());

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        ArgumentCaptor<Integer> gradientCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), gradientCaptor.capture(), any(), any());
        // 스냅샷(cycleVr.gradient()=10)이 아니라 램프 재계산값이 사용돼야 함
        assertThat(gradientCaptor.getValue()).isEqualTo(expectedGradient);
        assertThat(gradientCaptor.getValue()).isNotEqualTo(CYCLE_VR.gradient());
    }

    @Test
    @DisplayName("poolLimitRate 램프 재계산 — 새 사이클에 detail.poolLimitRateAt(weeks) 저장 (하강 확인)")
    void poolLimitRate_recalculatedFromRamp() {
        LocalDate firstStart = CYCLE_START.minusWeeks(150);
        StrategyCycle firstCycle = new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                new BigDecimal("500.00"), null, firstStart, null, Instant.now(), null);
        // poolLimitRate 램프만 활성 — gradient는 no-op(gMax=initialGradient)로 고정
        StrategyVrDetail rampingDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50"));
        LocalDate today = CYCLE_START.plusWeeks(4);

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(rampingDetail));
        when(strategyCyclePort.findFirstByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(firstCycle));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        long expectedWeeks = ChronoUnit.WEEKS.between(firstStart, today);
        BigDecimal expectedRate = rampingDetail.poolLimitRateAt(expectedWeeks);
        assertThat(expectedRate).isLessThan(rampingDetail.initialPoolLimitRate()); // 실제로 하강했는지 픽스처 유효성 확인

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        ArgumentCaptor<BigDecimal> rateCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), anyInt(), rateCaptor.capture(), any());
        assertThat(rateCaptor.getValue()).isEqualByComparingTo(expectedRate);
    }

    @Test
    @DisplayName("경과주수 0(등록 당일 롤오버) — initialGradient/initialPoolLimitRate 그대로 사용")
    void weeksZero_usesInitialRampValues() {
        // 최초 사이클 시작일 = today → 경과 0주
        LocalDate today = CYCLE_START.plusWeeks(4);
        StrategyCycle firstCycle = new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                new BigDecimal("500.00"), null, today, null, Instant.now(), null);
        StrategyVrDetail rampingDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 20, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50"));

        when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(CYCLE_VR));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(rampingDetail));
        when(strategyCyclePort.findFirstByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(firstCycle));
        when(cycleSnapshotCreator.createVrCycleAndSnapshot(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new StrategyCycle(UUID.randomUUID(), STRATEGY_ID, STRATEGY_VERSION_ID,
                        USD_DEPOSIT, null, today, null, Instant.now(), null));

        service.rollIfDue(ctx, POST_BALANCE, CLOSING_PRICE, today);

        ArgumentCaptor<Integer> gradientCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<BigDecimal> rateCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
                any(), any(), any(), any(), any(), gradientCaptor.capture(), rateCaptor.capture(), any());
        assertThat(gradientCaptor.getValue()).isEqualTo(10); // initialGradient
        assertThat(rateCaptor.getValue()).isEqualByComparingTo(new BigDecimal("0.75")); // initialPoolLimitRate
    }
}
