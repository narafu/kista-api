package com.kista.application.service.trading;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// CycleOrderComputer 단위 테스트: VR 분기 VrInputs 조립, fail-fast, 비VR null 유지
@ExtendWith(MockitoExtension.class)
class CycleOrderComputerTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock OrderPort orderPort;
    @Mock MarketCalendarPort marketCalendarPort;
    @Mock InfiniteStrategy infiniteStrategy;
    @Mock VrStrategy vrStrategy;
    @Mock PrivacyStrategy privacyStrategy;

    CycleOrderComputer computer;

    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();

    // INFINITE 전략
    static final Strategy INFINITE_STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    // VR 전략
    static final Strategy VR_STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.VR,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    // VR 사이클 (strategyVersionId 포함)
    static final StrategyCycle VR_CYCLE = new StrategyCycle(
            UUID.randomUUID(), VR_STRATEGY.id(), STRATEGY_VERSION_ID,
            new BigDecimal("5000.00"), null, LocalDate.now(), null, null, null);
    // INFINITE 사이클
    static final StrategyCycle INFINITE_CYCLE = new StrategyCycle(
            UUID.randomUUID(), INFINITE_STRATEGY.id(), STRATEGY_VERSION_ID,
            new BigDecimal("5000.00"), null, LocalDate.now(), null, null, null);

    static final AccountBalance BALANCE = new AccountBalance(0, null, new BigDecimal("5000.00"));
    static final BigDecimal CURRENT_PRICE = new BigDecimal("22.00");
    static final LocalDate VR_START_DATE = LocalDate.of(2026, 7, 6);
    static final LocalDate VR_TRADE_DATE = LocalDate.of(2026, 7, 10);

    @BeforeEach
    void setUp() {
        // INFINITE 리버스모드 판단 기본값 stub
        lenient().when(cyclePositionInfiniteDetailPort.findLatestByCycleId(any(), anyInt())).thenReturn(List.of());
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(any()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 20)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(any()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 20)));
        lenient().when(marketCalendarPort.isMarketOpen(any(LocalDate.class))).thenReturn(true);
        lenient().when(cyclePositionPort.findFirstOne(any())).thenAnswer(invocation -> Optional.of(
                CyclePosition.cycleStartSnapshot(invocation.getArgument(0), new BigDecimal("5000.00"), CURRENT_PRICE)));

        // CycleOrderStrategies에 InfiniteCycleOrderStrategy와 VrCycleOrderStrategy 등록
        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new VrCycleOrderStrategy(vrStrategy)));

        computer = new CycleOrderComputer(
                cycleStrategies, cyclePositionPort, cyclePositionInfiniteDetailPort,
                strategyInfiniteDetailPort, strategyCyclePort, strategyCycleVrPort, strategyVrDetailPort, orderPort,
                new TradingDayCounter(marketCalendarPort));
    }

    @Test
    @DisplayName("VR 전략 — VrInputs 4필드 모두 조립 후 buildOrders까지 전달")
    void compute_vrStrategy_assemblesVrInputsCorrectly() {
        // VR 사이클 상세 (value·poolLimitRate)
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        // VR 전략 버전 상세 (bandWidth)
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));
        BigDecimal poolUsed = new BigDecimal("300.00");

        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(VR_CYCLE.id())).thenReturn(poolUsed);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "테스트", CURRENT_PRICE);

        // VrInputs 조립 확인: value·bandWidth·poolLimit·poolUsed·currentPrice가 VrPosition에 전달됨
        verify(strategyCycleVrPort).findByCycleId(VR_CYCLE.id());
        verify(strategyVrDetailPort).findByStrategyVersionId(STRATEGY_VERSION_ID);
        verify(orderPort).sumFilledBuyAmountByCycleId(VR_CYCLE.id());
        // buildOrders에 currentPrice 전달 확인
        verify(vrStrategy).buildOrders(any(VrPosition.class), eq(Ticker.SOXL), eq(CURRENT_PRICE), eq(CURRENT_PRICE), any(LocalDate.class));
    }

    @Test
    @DisplayName("VR 전략 — opening position usdDeposit으로 pool limit을 계산한다")
    void compute_vrStrategy_derivesPoolLimitFromOpeningPositionDeposit() {
        StrategyCycle rolloverCycle = new StrategyCycle(
                UUID.randomUUID(), VR_STRATEGY.id(), STRATEGY_VERSION_ID,
                new BigDecimal("1600.00"), null, VR_START_DATE, null, null, null);
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                rolloverCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("0.50"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));
        CyclePosition openingPosition = CyclePosition.cycleStartSnapshot(
                rolloverCycle.id(), new BigDecimal("1000.00"), new BigDecimal("120.00"));

        when(strategyCycleVrPort.findByCycleId(rolloverCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(cyclePositionPort.findFirstOne(rolloverCycle.id())).thenReturn(Optional.of(openingPosition));
        when(orderPort.sumFilledBuyAmountByCycleId(rolloverCycle.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, null, VR_TRADE_DATE, rolloverCycle, null, "테스트", CURRENT_PRICE);

        var captor = org.mockito.ArgumentCaptor.forClass(VrPosition.class);
        verify(vrStrategy).buildOrders(captor.capture(), eq(Ticker.SOXL), eq(CURRENT_PRICE), eq(CURRENT_PRICE), eq(VR_TRADE_DATE));
        assertThat(captor.getValue().poolLimit()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("VR 전략 — opening pool limit의 0.005 센트를 HALF_UP으로 반올림한다")
    void compute_vrStrategy_roundsFractionalCentPoolLimitHalfUp() {
        StrategyCycle rolloverCycle = new StrategyCycle(
                UUID.randomUUID(), VR_STRATEGY.id(), STRATEGY_VERSION_ID,
                new BigDecimal("1600.00"), null, VR_START_DATE, null, null, null);
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                rolloverCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("0.50"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));
        CyclePosition openingPosition = CyclePosition.cycleStartSnapshot(
                rolloverCycle.id(), new BigDecimal("1000.01"), new BigDecimal("120.00"));

        when(strategyCycleVrPort.findByCycleId(rolloverCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(cyclePositionPort.findFirstOne(rolloverCycle.id())).thenReturn(Optional.of(openingPosition));
        when(orderPort.sumFilledBuyAmountByCycleId(rolloverCycle.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, null, VR_TRADE_DATE, rolloverCycle, null, "테스트", CURRENT_PRICE);

        var captor = org.mockito.ArgumentCaptor.forClass(VrPosition.class);
        verify(vrStrategy).buildOrders(captor.capture(), eq(Ticker.SOXL), eq(CURRENT_PRICE), eq(CURRENT_PRICE), eq(VR_TRADE_DATE));
        assertThat(captor.getValue().poolLimit()).isEqualByComparingTo("500.01");
        assertThat(captor.getValue().poolLimit().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("VR 전략 — opening position이 없으면 pool limit을 계산하지 않고 실패한다")
    void compute_vrStrategy_openingPositionMissing_throwsIllegalState() {
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("1000.00"), 10, new BigDecimal("0.50"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));
        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(cyclePositionPort.findFirstOne(VR_CYCLE.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                computer.compute(BALANCE, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "테스트", CURRENT_PRICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션 없음");
    }

    @Test
    @DisplayName("VR 사이클 상세 미존재 시 IllegalStateException — fail-fast")
    void compute_vrStrategy_cycleVrMissing_throwsIllegalState() {
        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                computer.compute(BALANCE, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "테스트", CURRENT_PRICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 사이클 상세 없음");
    }

    @Test
    @DisplayName("VR 전략 버전 상세 미존재 시 IllegalStateException — fail-fast")
    void compute_vrStrategy_vrDetailMissing_throwsIllegalState() {
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                computer.compute(BALANCE, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "테스트", CURRENT_PRICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 전략 버전 상세 없음");
    }

    @Test
    @DisplayName("INFINITE 전략 — VR 포트 미호출 (비VR 경로 무회귀)")
    void compute_infiniteStrategy_doesNotCallVrPorts() {
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of());

        computer.compute(BALANCE, INFINITE_STRATEGY, new BigDecimal("20.00"),
                LocalDate.now(), INFINITE_CYCLE, null, "테스트", CURRENT_PRICE);

        // VR 전용 포트 미호출 확인
        verify(strategyCycleVrPort, never()).findByCycleId(any());
        verify(strategyVrDetailPort, never()).findByStrategyVersionId(any());
        verify(orderPort, never()).sumFilledBuyAmountByCycleId(any());
    }

    // ── plan() 경유 생성 시점 cap 미적용 통합 검증 (real VrStrategy) ──────────────

    @Test
    @DisplayName("VR plan() 경유 — 생성 시점 가격 캡 미적용 (rung 단가가 currentPrice 기준 cap을 초과해도 그대로 유지)")
    void compute_vrStrategy_doesNotCapAtPlanTime() {
        // 실제 VrStrategy + VrCycleOrderStrategy 조립 (mock stub 없음)
        // Task 2: VR 생성 시점 cap을 제거하고 접수 전 BuyOrderPriceCapper(VR_POSITION)로 이전 — plan()은 더 이상 캡을 적용하지 않는다
        VrStrategy realVrStrategy = new VrStrategy();
        VrCycleOrderStrategy realVrCycleStrategy = new VrCycleOrderStrategy(realVrStrategy);
        CycleOrderStrategies realCycleStrategies = new CycleOrderStrategies(List.of(realVrCycleStrategy));
        CycleOrderComputer realComputer = new CycleOrderComputer(
                realCycleStrategies, cyclePositionPort, cyclePositionInfiniteDetailPort,
                strategyInfiniteDetailPort, strategyCyclePort, strategyCycleVrPort, strategyVrDetailPort, orderPort,
                new TradingDayCounter(marketCalendarPort));

        // 가격 캡 트리거 픽스처:
        // holdings=1, value=10000, bandWidth=15% → lowerBand=8500, buyPrice(m=1) = 8500/1 = 8500
        // currentPrice=700 → PriceCapPolicy 기준 cap = 700×1.05 = 735.00 (8500 > 735)
        // usdDeposit을 충분히 크게 둬(예산 부족으로 rung 자체가 제외되지 않도록) 순수 cap 미적용 여부만 검증
        BigDecimal currentPrice = new BigDecimal("700.00");
        BigDecimal cap = PriceCapPolicy.capFor(currentPrice); // 735.00
        AccountBalance balance = new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("100000.00"));
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("10000.00"), 10, new BigDecimal("5000.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));

        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(VR_CYCLE.id())).thenReturn(BigDecimal.ZERO);

        Optional<CycleOrderStrategy.OrderPlan> planOpt = realComputer.compute(
                balance, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "캡미적용테스트", currentPrice);

        assertThat(planOpt).isPresent();
        // vrPosition도 함께 실려 있어야 BuyOrderPriceCapper(VR_POSITION)의 접수 전 보정이 가능하다
        assertThat(planOpt.get().vrPosition()).isNotNull();
        List<Order> buyOrders = planOpt.get().orders().stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .toList();
        // holdings=1이므로 매수 주문이 생성됨
        assertThat(buyOrders).isNotEmpty();
        // 첫 rung 가격(8500.00)이 cap(735.00)을 초과한 원가 그대로 유지 — plan() 단계에서 캡이 적용되지 않았다는 증거
        assertThat(buyOrders.getFirst().price()).isEqualByComparingTo("8500.00");
        assertThat(buyOrders.getFirst().price()).isGreaterThan(cap);
    }

    @Test
    @DisplayName("VR currentPrice null — buildOrders에도 null 전달 (캡 미적용)")
    void compute_vrStrategy_nullCurrentPrice_passesNullToBuildOrders() {
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));

        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(VR_CYCLE.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        // currentPrice=null (수동 실행·preview 경로)
        computer.compute(BALANCE, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "테스트", null);

        // buildOrders에 currentPrice=null 전달 확인
        verify(vrStrategy).buildOrders(any(VrPosition.class), eq(Ticker.SOXL), isNull(), isNull(), any(LocalDate.class));
    }

    @Test
    @DisplayName("VR preview — currentPrice가 없으면 prevClosePrice를 주문 기준가격으로 전달한다")
    void compute_vrStrategy_previewUsesPrevCloseAsReferencePrice() {
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), BigDecimal.ZERO, 10, BigDecimal.ZERO);
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 200,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));
        BigDecimal prevClosePrice = new BigDecimal("100.00");

        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(VR_CYCLE.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, prevClosePrice, LocalDate.now(), VR_CYCLE, null, "preview", null);

        verify(vrStrategy).buildOrders(any(VrPosition.class), eq(Ticker.SOXL), eq(prevClosePrice), isNull(), any(LocalDate.class));
    }

    @Test
    @DisplayName("VR 전략 — 첫 사이클 bootstrap 메타데이터를 VrPosition에 전달한다")
    void compute_vrStrategy_passesInitialBootstrapMetadata() {
        StrategyCycle firstCycle = new StrategyCycle(
                UUID.randomUUID(), VR_STRATEGY.id(), STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, VR_START_DATE, null, null, null);
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                firstCycle.id(), BigDecimal.ZERO, 10, BigDecimal.ZERO);
        StrategyVrDetail vrDetail = new StrategyVrDetail(
                STRATEGY_VERSION_ID, 2, new BigDecimal("15.00"), 200,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));

        when(strategyCyclePort.findFirstByStrategyId(VR_STRATEGY.id())).thenReturn(Optional.of(firstCycle));
        when(strategyCycleVrPort.findByCycleId(firstCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(firstCycle.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, null, VR_TRADE_DATE, firstCycle, null, "테스트", CURRENT_PRICE);

        var captor = org.mockito.ArgumentCaptor.forClass(VrPosition.class);
        verify(vrStrategy).buildOrders(captor.capture(), eq(Ticker.SOXL), eq(CURRENT_PRICE), eq(CURRENT_PRICE), eq(VR_TRADE_DATE));
        VrPosition captured = captor.getValue();
        assertThat(captured.firstCycle()).isTrue();
        assertThat(captured.cycleDue()).isFalse();
        assertThat(captured.remainingTradingDays()).isGreaterThan(0);
        assertThat(captured.recurringAmount()).isEqualTo(200);
    }

    @Test
    @DisplayName("VR 최초 사이클은 position snapshot이 누적되어도 적립식 bootstrap을 유지한다")
    void compute_vrStrategy_firstRegisteredCycleKeepsBootstrapAfterSnapshots() {
        StrategyCycle firstCycle = new StrategyCycle(
                UUID.randomUUID(), VR_STRATEGY.id(), STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, VR_START_DATE, null, null, null);
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                firstCycle.id(), BigDecimal.ZERO, 10, BigDecimal.ZERO);
        StrategyVrDetail vrDetail = new StrategyVrDetail(
                STRATEGY_VERSION_ID, 2, new BigDecimal("15.00"), 200,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));

        when(strategyCyclePort.findFirstByStrategyId(VR_STRATEGY.id())).thenReturn(Optional.of(firstCycle));
        when(strategyCycleVrPort.findByCycleId(firstCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(firstCycle.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, null, VR_START_DATE.plusWeeks(2), firstCycle, null, "테스트", CURRENT_PRICE);

        var captor = org.mockito.ArgumentCaptor.forClass(VrPosition.class);
        verify(vrStrategy).buildOrders(captor.capture(), eq(Ticker.SOXL), eq(CURRENT_PRICE), eq(CURRENT_PRICE), eq(VR_START_DATE.plusWeeks(2)));
        assertThat(captor.getValue().firstCycle()).isTrue();
        assertThat(captor.getValue().cycleDue()).isTrue();
    }

    @Test
    @DisplayName("VR 롤오버 사이클은 초기 스냅샷만 있어도 bootstrap으로 보지 않는다")
    void compute_vrStrategy_rolloverCycleIsNotBootstrap() {
        StrategyCycle firstCycle = new StrategyCycle(
                UUID.randomUUID(), VR_STRATEGY.id(), STRATEGY_VERSION_ID,
                new BigDecimal("1000.00"), null, VR_START_DATE.minusWeeks(2), closingDate(), null, null);
        StrategyCycle rolloverCycle = new StrategyCycle(
                UUID.randomUUID(), VR_STRATEGY.id(), STRATEGY_VERSION_ID,
                new BigDecimal("1000.00"), null, VR_START_DATE, null, null, null);
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                rolloverCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("500.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(
                STRATEGY_VERSION_ID, 2, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));

        when(strategyCyclePort.findFirstByStrategyId(VR_STRATEGY.id())).thenReturn(Optional.of(firstCycle));
        when(strategyCycleVrPort.findByCycleId(rolloverCycle.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(rolloverCycle.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), any(), any(), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, null, VR_TRADE_DATE, rolloverCycle, null, "테스트", CURRENT_PRICE);

        var captor = org.mockito.ArgumentCaptor.forClass(VrPosition.class);
        verify(vrStrategy).buildOrders(captor.capture(), eq(Ticker.SOXL), eq(CURRENT_PRICE), eq(CURRENT_PRICE), eq(VR_TRADE_DATE));
        assertThat(captor.getValue().firstCycle()).isFalse();
    }

    private static LocalDate closingDate() {
        return VR_START_DATE.minusDays(1);
    }
}
