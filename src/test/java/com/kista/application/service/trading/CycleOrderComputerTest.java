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
import java.math.RoundingMode;
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
    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock OrderPort orderPort;
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

    @BeforeEach
    void setUp() {
        // INFINITE 리버스모드 판단 기본값 stub
        lenient().when(cyclePositionInfiniteDetailPort.findLatestByCycleId(any(), anyInt())).thenReturn(List.of());
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(any()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 20)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(any()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 20)));

        // CycleOrderStrategies에 InfiniteCycleOrderStrategy와 VrCycleOrderStrategy 등록
        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new VrCycleOrderStrategy(vrStrategy)));

        computer = new CycleOrderComputer(
                cycleStrategies, cyclePositionPort, cyclePositionInfiniteDetailPort,
                strategyInfiniteDetailPort, strategyCycleVrPort, strategyVrDetailPort, orderPort);
    }

    @Test
    @DisplayName("VR 전략 — VrInputs 4필드 모두 조립 후 buildOrders까지 전달")
    void compute_vrStrategy_assemblesVrInputsCorrectly() {
        // VR 사이클 상세 (value·poolLimit)
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        // VR 전략 버전 상세 (bandWidth)
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0);
        BigDecimal poolUsed = new BigDecimal("300.00");

        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(VR_CYCLE.id())).thenReturn(poolUsed);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), eq(CURRENT_PRICE), any()))
                .thenReturn(List.of());

        computer.compute(BALANCE, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "테스트", CURRENT_PRICE);

        // VrInputs 조립 확인: value·bandWidth·poolLimit·poolUsed·currentPrice가 VrPosition에 전달됨
        verify(strategyCycleVrPort).findByCycleId(VR_CYCLE.id());
        verify(strategyVrDetailPort).findByStrategyVersionId(STRATEGY_VERSION_ID);
        verify(orderPort).sumFilledBuyAmountByCycleId(VR_CYCLE.id());
        // buildOrders에 currentPrice 전달 확인
        verify(vrStrategy).buildOrders(any(VrPosition.class), eq(Ticker.SOXL), eq(CURRENT_PRICE), any(LocalDate.class));
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

    // ── plan() 경유 가격 캡 통합 검증 (real VrStrategy) ──────────────────────────

    @Test
    @DisplayName("VR plan() 경유 가격 캡 통합 — rung 단가가 currentPrice×1.10 초과 시 cap으로 클램프")
    void compute_vrStrategy_currentPriceCap_clampedViaPlanPath() {
        // 실제 VrStrategy + VrCycleOrderStrategy 조립 (mock stub 없음)
        // Task 1의 VrStrategyTypeTest가 buildOrders 레벨 캡을 검증; 여기서는 plan() 경유 currentPrice 전달 경로 검증
        VrStrategy realVrStrategy = new VrStrategy();
        VrCycleOrderStrategy realVrCycleStrategy = new VrCycleOrderStrategy(realVrStrategy);
        CycleOrderStrategies realCycleStrategies = new CycleOrderStrategies(List.of(realVrCycleStrategy));
        CycleOrderComputer realComputer = new CycleOrderComputer(
                realCycleStrategies, cyclePositionPort, cyclePositionInfiniteDetailPort,
                strategyInfiniteDetailPort, strategyCycleVrPort, strategyVrDetailPort, orderPort);

        // 가격 캡 트리거 픽스처:
        // holdings=1, value=10000, bandWidth=15% → lowerBand=8500
        // buyPrice(m=1) = 8500/1 = 8500
        // currentPrice=700 → cap = 700×1.10 = 770.00
        // 8500 > 770 → 캡 적용으로 모든 BUY 주문 가격 ≤ 770.00 이어야 함
        BigDecimal currentPrice = new BigDecimal("700.00");
        BigDecimal cap = currentPrice.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP); // 770.00
        AccountBalance balance = new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("5000.00"));
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("10000.00"), 10, new BigDecimal("5000.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0);

        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(VR_CYCLE.id())).thenReturn(BigDecimal.ZERO);

        Optional<CycleOrderStrategy.OrderPlan> planOpt = realComputer.compute(
                balance, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "캡테스트", currentPrice);

        assertThat(planOpt).isPresent();
        List<Order> buyOrders = planOpt.get().orders().stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .toList();
        // holdings=1이므로 매수 주문이 생성됨
        assertThat(buyOrders).isNotEmpty();
        // 모든 BUY 주문 가격이 cap(770.00) 이하여야 함 — 캡이 plan()→buildOrders() 경로로 정상 전달됨을 검증
        assertThat(buyOrders).allMatch(o -> o.price().compareTo(cap) <= 0,
                "매수 주문 가격이 cap(" + cap + ")을 초과하면 안 됨 — currentPrice가 buildOrders까지 전달되지 않은 것");
    }

    @Test
    @DisplayName("VR currentPrice null — buildOrders에도 null 전달 (캡 미적용)")
    void compute_vrStrategy_nullCurrentPrice_passesNullToBuildOrders() {
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                VR_CYCLE.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
        StrategyVrDetail vrDetail = new StrategyVrDetail(STRATEGY_VERSION_ID, 4, new BigDecimal("15.00"), 0);

        when(strategyCycleVrPort.findByCycleId(VR_CYCLE.id())).thenReturn(Optional.of(cycleVr));
        when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(vrDetail));
        when(orderPort.sumFilledBuyAmountByCycleId(VR_CYCLE.id())).thenReturn(BigDecimal.ZERO);
        when(vrStrategy.buildOrders(any(VrPosition.class), eq(Ticker.SOXL), isNull(), any()))
                .thenReturn(List.of());

        // currentPrice=null (수동 실행·preview 경로)
        computer.compute(BALANCE, VR_STRATEGY, null, LocalDate.now(), VR_CYCLE, null, "테스트", null);

        // buildOrders에 currentPrice=null 전달 확인
        verify(vrStrategy).buildOrders(any(VrPosition.class), eq(Ticker.SOXL), isNull(), any(LocalDate.class));
    }
}
