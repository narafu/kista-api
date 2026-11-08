package com.kista.tradingcore.trading.application.service;

import static com.kista.shared.support.FixedClockTestConfig.FIXED;
import com.kista.tradingcore.trading.domain.creation.VrRampInput;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.CancelResult;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.trading.application.event.NewCycleStartedEvent;
import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import com.kista.tradingcore.trading.domain.model.ReconfigureVrCommand;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyCycleVrDetail;
import com.kista.tradingcore.matching.domain.model.StrategyVrDetail;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyCycleVrPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyVrDetailPort;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static com.kista.tradingcore.support.StrategyCommands.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyCycleSeedType;

// VrReconfigureService 단위 테스트 — 순수 파라미터 수정/투자금 추가/램프 시계·검증/소유권 분기 검증
// package-private VrReconfigureService와 같은 패키지에 위치 (application.service.trading)
@ExtendWith(MockitoExtension.class)
@DisplayName("VrReconfigureService 단위 테스트")
class VrReconfigureServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CycleSnapshotCreator cycleSnapshotCreator;
    @Mock OrderCancelService orderCancelService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BrokerPricePort pricePort;

    VrReconfigureService service;

    private final UUID strategyId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID strategyVersionId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();

    private Account account;
    private Strategy vrStrategy;
    private StrategyCycle currentCycle;
    private StrategyVrDetail currentDetail;
    private StrategyCycleVrDetail currentCycleVr;
    private CyclePosition latestPosition;
    private StrategyCycle newCycleAfterReconfigure;
    private BigDecimal currentPrice;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        service = new VrReconfigureService(strategyPort, accountPort, strategyCyclePort, strategyVrDetailPort, strategyCycleVrPort,
                cyclePositionPort, pricePort, cycleSnapshotCreator, orderCancelService, eventPublisher, FIXED);
        account = TradingFixtures.kisAccount(accountId, requesterId);
        vrStrategy = new Strategy(strategyId, accountId, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        currentCycle = new StrategyCycle(cycleId, strategyId, strategyVersionId,
                BigDecimal.valueOf(1000), null, LocalDate.now(FIXED).minusWeeks(4), null, null, null);
        // 기본 램프: initialGradient=10/gMax=15/gGraceWeeks=gStepWeeks=52,26 / initialPoolLimitRate=0.75/floor=0.50
        currentDetail = new StrategyVrDetail(strategyVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 15, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50"));
        currentCycleVr = new StrategyCycleVrDetail(cycleId, new BigDecimal("1000.00"), 10, new BigDecimal("0.75"));
        latestPosition = new CyclePosition(UUID.randomUUID(), cycleId,
                new BigDecimal("500.00"), new BigDecimal("95.00"), new BigDecimal("50.00"), BigDecimal.valueOf(10), null, null);
        newCycleAfterReconfigure = new StrategyCycle(UUID.randomUUID(), strategyId, UUID.randomUUID(),
                BigDecimal.valueOf(500), null, LocalDate.now(FIXED), null, null, null);
        currentPrice = new BigDecimal("120.00");
        // 서비스 내부와 동일한 SSOT 호출 — DstInfo.nextTradeDate(고정 시각)를 테스트에서도 그대로 재사용
        today = com.kista.tradingcore.trading.domain.model.DstInfo.nextTradeDate(ZonedDateTime.now(FIXED));
    }

    // reconfigure()가 검증을 통과해 cycleSnapshotCreator.reconfigureVrCycle까지 도달하는 공통 스텁 체인.
    // 일부 테스트(램프 검증 실패)는 이 체인 중 뒷부분(findFirstByStrategyId 이후) 스텁이 실제로는 호출되지 않으므로 lenient 처리.
    private void stubHappyPathChain(LocalDate firstStartDate) {
        lenient().when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(vrStrategy);
        lenient().when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(account);
        lenient().when(pricePort.getPrice(StrategyTicker.TQQQ, account.toBrokerRef())).thenReturn(currentPrice);
        lenient().when(orderCancelService.cancelByCycle(strategyId, requesterId)).thenReturn(new CancelResult(0, 0));
        lenient().when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(currentCycle);
        lenient().when(strategyVrDetailPort.findByStrategyVersionId(strategyVersionId)).thenReturn(Optional.of(currentDetail));
        lenient().when(strategyCycleVrPort.findByCycleId(cycleId)).thenReturn(Optional.of(currentCycleVr));
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(strategyId)).thenReturn(Optional.of(latestPosition));
        StrategyCycle firstCycle = new StrategyCycle(UUID.randomUUID(), strategyId, strategyVersionId,
                BigDecimal.valueOf(500), null, firstStartDate, null, null, null);
        lenient().when(strategyCyclePort.findFirstByStrategyId(strategyId)).thenReturn(Optional.of(firstCycle));
        lenient().when(cycleSnapshotCreator.reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class)))
                .thenReturn(newCycleAfterReconfigure);
    }

    private void stubHappyPathChain() {
        stubHappyPathChain(today.minusWeeks(10));
    }

    // cycleSnapshotCreator.reconfigureVrCycle(...) 호출 인자 전체를 캡처해 검증하기 위한 홀더
    private record CapturedCall(
            UUID strategyId, UUID currentCycleId,
            Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount,
            Integer initialGradient, Integer gGraceWeeks, Integer gStepWeeks, Integer gMax,
            BigDecimal initialPoolLimitRate, Integer pGraceWeeks, Integer pStepWeeks, BigDecimal poolLimitFloor,
            AccountBalance postBalance, BigDecimal closingPrice, BigDecimal newValue, Long weeks) {
    }

    private CapturedCall captureReconfigureCall() {
        ArgumentCaptor<CycleSnapshotCreator.VrReconfiguration> captor =
                ArgumentCaptor.forClass(CycleSnapshotCreator.VrReconfiguration.class);
        verify(cycleSnapshotCreator).reconfigureVrCycle(captor.capture());
        CycleSnapshotCreator.VrReconfiguration r = captor.getValue();
        var ramp = r.ramp();
        return new CapturedCall(
                r.strategyId(), r.currentCycleId(),
                r.intervalWeeks(), r.bandWidth(), r.recurringAmount(),
                ramp.initialGradient(), ramp.gGraceWeeks(), ramp.gStepWeeks(), ramp.gMax(),
                ramp.initialPoolLimitRate(), ramp.pGraceWeeks(), ramp.pStepWeeks(), ramp.poolLimitFloor(),
                r.postBalance(), r.closingPrice(), r.newValue(), r.weeks());
    }

    @Test
    void reconfigure_rejects_archived_strategy() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(new Strategy(strategyId, accountId, StrategyType.VR,
                StrategyStatus.ARCHIVED, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE));
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(account);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, reconfigureVr().bandWidth("20.00").build()))
                .isInstanceOf(IllegalStateException.class).hasMessage("보관된 전략은 변경할 수 없습니다.");

        verify(orderCancelService, never()).cancelByCycle(any(), any());
    }

    // --- 1) 순수 파라미터 수정 (투자금 추가 없음) ---

    @Test
    @DisplayName("순수 파라미터 수정: bandWidth만 변경 → V 이월, holdings/usdDeposit 불변, 나머지 램프값 상속")
    void reconfigure_pureParamChange_carriesOverBalanceAndValue() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().bandWidth("20.00").build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.bandWidth()).isEqualByComparingTo("20.00");
        // 나머지 램프 파라미터는 미지정이므로 현재 활성 버전 값 그대로 상속
        assertThat(captured.intervalWeeks()).isEqualTo(currentDetail.intervalWeeks());
        assertThat(captured.recurringAmount()).isEqualTo(currentDetail.recurringAmount());
        assertThat(captured.initialGradient()).isEqualTo(currentDetail.initialGradient());
        assertThat(captured.gGraceWeeks()).isEqualTo(currentDetail.gGraceWeeks());
        assertThat(captured.gStepWeeks()).isEqualTo(currentDetail.gStepWeeks());
        assertThat(captured.gMax()).isEqualTo(currentDetail.gMax());
        assertThat(captured.initialPoolLimitRate()).isEqualByComparingTo(currentDetail.initialPoolLimitRate());
        assertThat(captured.pGraceWeeks()).isEqualTo(currentDetail.pGraceWeeks());
        assertThat(captured.pStepWeeks()).isEqualTo(currentDetail.pStepWeeks());
        assertThat(captured.poolLimitFloor()).isEqualByComparingTo(currentDetail.poolLimitFloor());
        // 투자금 추가 없음 → holdings/usdDeposit 불변, V 이월
        assertThat(captured.postBalance().holdings()).isEqualTo(latestPosition.holdings());
        assertThat(captured.postBalance().usdDeposit()).isEqualByComparingTo(latestPosition.usdDeposit());
        assertThat(captured.newValue()).isEqualByComparingTo(currentCycleVr.value());

        verify(orderCancelService).cancelByCycle(strategyId, requesterId);
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(requesterId));
    }

    // --- 2) 수량 주입 ---

    @Test
    @DisplayName("수량 주입: holdings 증가 + 평단가 가중평균 + V += 주입수량×현재가")
    void reconfigure_injectShares_updatesHoldingsAvgPriceAndValue() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().injectShares(5, "60.00").build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        // newHoldings = 기존 10 + 5 = 15
        assertThat(captured.postBalance().wholeShares()).isEqualTo(15);
        // 가중평균: (50.00×10 + 60.00×5) / 15 = 800.00/15 = 53.33 (scale 2 HALF_UP)
        BigDecimal expectedAvgPrice = new BigDecimal("500.00").add(new BigDecimal("300.00"))
                .divide(BigDecimal.valueOf(15), 2, java.math.RoundingMode.HALF_UP);
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(expectedAvgPrice);
        // V += 5 × 120.00 = 600.00 → 1000.00 + 600.00 = 1600.00
        assertThat(captured.newValue()).isEqualByComparingTo("1600.00");
    }

    @Test
    @DisplayName("사용자 알림 실패해도 재설정 결과는 반환되고, 관리자에게 notifyError로 별도 기록됨")
    void reconfigure_userNotificationFails_stillReturnsResultButNotifiesAdmin() {
        stubHappyPathChain();
        // any()는 publishEvent(ApplicationEvent) 오버로드로 잘못 해석돼(다른 record 이벤트가 실제로 타는
        // publishEvent(Object)와 다른 메서드) 스텁이 전혀 매칭되지 않는다 — 실패시킬 이벤트 타입을 명시해야 함.
        // 이 이벤트 타입만 실패시켜야 카탈로그 fallback(TradingErrorEvent 발행)까지 함께 실패하지 않는다.
        org.mockito.Mockito.doThrow(new RuntimeException("텔레그램 발송 실패"))
                .when(eventPublisher).publishEvent(any(NewCycleStartedEvent.class));
        // 자본 변경 이벤트는 정상 발행 — strict stubs의 인자 불일치 오탐 방지
        org.mockito.Mockito.lenient().doNothing().when(eventPublisher)
                .publishEvent(any(com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent.class));
        ReconfigureVrCommand cmd = reconfigureVr().bandWidth("20.00").build();

        service.reconfigure(strategyId, requesterId, cmd);

        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent));
    }

    // --- 3) 예수금 주입 ---

    @Test
    @DisplayName("예수금 주입: usdDeposit 증가, holdings/avgPrice 불변, V 이월")
    void reconfigure_injectDeposit_updatesDepositOnly() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().injectDeposit("200.00").build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.postBalance().usdDeposit()).isEqualByComparingTo("700.00"); // 500.00 + 200.00
        assertThat(captured.postBalance().holdings()).isEqualTo(latestPosition.holdings());
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(latestPosition.avgPrice());
        // 수량 주입 없으므로 V 이월 (증분 없음)
        assertThat(captured.newValue()).isEqualByComparingTo(currentCycleVr.value());
    }

    // --- 3-1) 투자금 인출 ---

    @Test
    @DisplayName("수량 인출: holdings 감소, 평단가는 그대로 유지, V -= 인출수량×현재가")
    void reconfigure_withdrawShares_reducesHoldingsKeepsAvgPriceReducesValue() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().withdrawShares(4).build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        // newHoldings = 기존 10 - 4 = 6
        assertThat(captured.postBalance().wholeShares()).isEqualTo(6);
        // 인출은 잔여 수량의 원가를 바꾸지 않음 — 평단가 그대로
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(latestPosition.avgPrice());
        // V -= 4 × 120.00 = 480.00 → 1000.00 - 480.00 = 520.00
        assertThat(captured.newValue()).isEqualByComparingTo("520.00");
    }

    @Test
    @DisplayName("예수금 인출: usdDeposit 감소, holdings/avgPrice 불변, V 이월")
    void reconfigure_withdrawDeposit_reducesDepositOnly() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().withdrawDeposit("150.00").build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.postBalance().usdDeposit()).isEqualByComparingTo("350.00"); // 500.00 - 150.00
        assertThat(captured.postBalance().holdings()).isEqualTo(latestPosition.holdings());
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(latestPosition.avgPrice());
        assertThat(captured.newValue()).isEqualByComparingTo(currentCycleVr.value());
    }

    @Test
    @DisplayName("투자금 인출 주식 수가 보유 수량을 초과 → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_withdrawSharesExceedsHoldings_throws() {
        stubHappyPathChain();
        // latestPosition.holdings() == 10
        ReconfigureVrCommand cmd = reconfigureVr().withdrawShares(11).build();

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
    }

    @Test
    @DisplayName("투자금 인출 예수금이 보유 예수금을 초과 → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_withdrawDepositExceedsBalance_throws() {
        stubHappyPathChain();
        // latestPosition.usdDeposit() == 500.00
        ReconfigureVrCommand cmd = reconfigureVr().withdrawDeposit("500.01").build();

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
    }

    @Test
    @DisplayName("수량 주입 평단가 경계: 원값 45.12495 → scale 2 HALF_UP 45.12 (4자리 선반올림 경유 금지)")
    void reconfigure_injectShares_avgPriceRoundsDirectlyToScale2() {
        latestPosition = new CyclePosition(UUID.randomUUID(), cycleId,
                new BigDecimal("500.00"), new BigDecimal("95.00"), new BigDecimal("45.12"), BigDecimal.valueOf(199), null, null);
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().injectShares(1, "46.11").build();

        service.reconfigure(strategyId, requesterId, cmd);

        // (199×45.12 + 46.11) / 200 = 45.12495
        assertThat(captureReconfigureCall().postBalance().avgPrice()).isEqualTo(new BigDecimal("45.12"));
    }

    @Test
    @DisplayName("인출 후 주입: 잔여 수량 기준으로 평단가 가중평균 재계산")
    void reconfigure_withdrawThenInject_recomputesAvgPriceFromRemainingShares() {
        stubHappyPathChain();
        // 기존 10주(평단가 50.00) 중 4주 인출 → 잔여 6주(원가 300.00), 이후 3주를 60.00에 주입
        ReconfigureVrCommand cmd = reconfigureVr().injectShares(3, "60.00").withdrawShares(4).build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        // newHoldings = (10 - 4) + 3 = 9
        assertThat(captured.postBalance().wholeShares()).isEqualTo(9);
        // 가중평균: (50.00×6 + 60.00×3) / 9 = (300.00+180.00)/9 = 480.00/9
        BigDecimal expectedAvgPrice = new BigDecimal("300.00").add(new BigDecimal("180.00"))
                .divide(BigDecimal.valueOf(9), 2, java.math.RoundingMode.HALF_UP);
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(expectedAvgPrice);
    }

    // --- 4) 비-VR 전략 ---

    @Test
    @DisplayName("비-VR 전략 재설정 시도 → IllegalArgumentException")
    void reconfigure_nonVrStrategy_throwsIllegalArgumentException() {
        Strategy infiniteStrategy = new Strategy(strategyId, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(infiniteStrategy);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(account);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, reconfigureVr().build()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(orderCancelService, never()).cancelByCycle(any(), any());
    }

    // --- 5) 소유권 불일치 ---

    @Test
    @DisplayName("소유권 불일치 → SecurityException 그대로 전파")
    void reconfigure_ownershipMismatch_propagatesSecurityException() {
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(vrStrategy);
        when(accountPort.requireOwnedAccount(accountId, requesterId))
                .thenThrow(new SecurityException("계좌에 대한 접근 권한이 없습니다"));

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, reconfigureVr().build()))
                .isInstanceOf(SecurityException.class);

        verify(orderCancelService, never()).cancelByCycle(any(), any());
    }

    // --- 6) 램프 시계 유지 확인 ---

    @Test
    @DisplayName("램프 시계는 최초 사이클 startDate 기준 — cmd로 램프값을 새로 지정해도 weeks 계산 자체는 불변")
    void reconfigure_rampClock_basedOnFirstCycleStartDate_regardlessOfCmdOverrides() {
        // 최초 시작일을 오늘로부터 정확히 60주 전으로 고정 → weeks=60 기대
        stubHappyPathChain(today.minusWeeks(60));
        // initialGradient를 새 값(12, gMax=15 이내)으로 재지정해도 weeks 계산 자체는 firstStart 기준 그대로여야 함
        ReconfigureVrCommand cmd = reconfigureVr().vrRamp(new VrRampInput(12, null, null, null, null, null, null, null)).build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.weeks()).isEqualTo(60L);
        // 사전 계산 검증: ChronoUnit.WEEKS.between(firstStart, today)와 동일한 값이어야 함
        assertThat(captured.weeks()).isEqualTo(ChronoUnit.WEEKS.between(today.minusWeeks(60), today));
        // 램프값 자체는 cmd 재지정이 정상 반영됨 (weeks 계산과는 독립적으로 갱신)
        assertThat(captured.initialGradient()).isEqualTo(12);
    }

    // --- 6-b) 시작 전 재설정 — 첫 사이클 in-place 갱신 ---

    @Test
    @DisplayName("시작예정일이 미래면 reconfigureVrCycleBeforeStart 호출 — weeks=0, 새 사이클 알림 없음, 캐시 무효화 이벤트는 발행")
    void reconfigure_beforeStart_updatesInPlace() {
        stubHappyPathChain(LocalDate.now(FIXED).plusDays(5)); // 최초 사이클도 미래 시작 → 경과 주수 음수, 0으로 보정
        currentCycle = new StrategyCycle(cycleId, strategyId, strategyVersionId,
                BigDecimal.valueOf(1000), null, LocalDate.now(FIXED).plusDays(5), null, null, null);
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(currentCycle);

        service.reconfigure(strategyId, requesterId, reconfigureVr().bandWidth("20.00").build());

        ArgumentCaptor<CycleSnapshotCreator.VrReconfiguration> captor =
                ArgumentCaptor.forClass(CycleSnapshotCreator.VrReconfiguration.class);
        verify(cycleSnapshotCreator).reconfigureVrCycleBeforeStart(captor.capture());
        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
        assertThat(captor.getValue().weeks()).isZero(); // 최초 사이클도 미래 시작 → 음수 보정 0
        verify(orderCancelService).cancelByCycle(strategyId, requesterId);
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(requesterId));
        verify(eventPublisher, never()).publishEvent(any(NewCycleStartedEvent.class));
    }

    @Test
    @DisplayName("시작 전 경로여도 램프 weeks는 최초 사이클 기준 — 당일 사이클이 최초가 아니면 리셋하지 않는다")
    void reconfigure_beforeStart_keepsRampClockFromFirstCycle() {
        stubHappyPathChain(today.minusWeeks(10));
        currentCycle = new StrategyCycle(cycleId, strategyId, strategyVersionId,
                BigDecimal.valueOf(1000), null, today, null, null, null); // startDate == nextTradeDate → 시작 전
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(currentCycle);

        service.reconfigure(strategyId, requesterId, reconfigureVr().bandWidth("20.00").build());

        ArgumentCaptor<CycleSnapshotCreator.VrReconfiguration> captor =
                ArgumentCaptor.forClass(CycleSnapshotCreator.VrReconfiguration.class);
        verify(cycleSnapshotCreator).reconfigureVrCycleBeforeStart(captor.capture());
        assertThat(captor.getValue().weeks()).isEqualTo(10L);
    }

    // 시작예정일 당일 04:30 KST(DstInfo.nextTradeDate 경계) 전후 — 04:29는 다음 거래일=당일이라 시작 전, 04:31은 다음 거래일=내일이라 시작 후
    @Test
    @DisplayName("경계: 시작예정일 당일 04:29 KST는 시작 전 경로, 04:31 KST는 시작 후 경로")
    void reconfigure_boundaryAtStartDate_switchesPathAround0430Kst() {
        stubHappyPathChain();
        LocalDate startDate = LocalDate.of(2026, 1, 6); // 화요일
        currentCycle = new StrategyCycle(cycleId, strategyId, strategyVersionId,
                BigDecimal.valueOf(1000), null, startDate, null, null, null);
        when(strategyCyclePort.requireLatestByStrategyId(strategyId)).thenReturn(currentCycle);

        for (boolean before : new boolean[]{true, false}) {
            Clock clock = Clock.fixed(
                    startDate.atTime(4, before ? 29 : 31).atZone(TimeZones.KST).toInstant(), TimeZones.KST);
            VrReconfigureService boundaryService = new VrReconfigureService(strategyPort, accountPort, strategyCyclePort,
                    strategyVrDetailPort, strategyCycleVrPort, cyclePositionPort, pricePort, cycleSnapshotCreator,
                    orderCancelService, eventPublisher, clock);

            boundaryService.reconfigure(strategyId, requesterId, reconfigureVr().bandWidth("20.00").build());

            if (before) {
                verify(cycleSnapshotCreator).reconfigureVrCycleBeforeStart(any(CycleSnapshotCreator.VrReconfiguration.class));
                verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
            } else {
                verify(cycleSnapshotCreator).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
            }
        }
    }

    // --- 7) 램프 검증 실패 ---

    @Test
    @DisplayName("gMax < initialGradient → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_invalidRamp_gMaxLessThanInitialGradient_throws() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().vrRamp(new VrRampInput(20, null, null, 15, null, null, null, null)).build();

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
    }

    @Test
    @DisplayName("poolLimitFloor > initialPoolLimitRate → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_invalidRamp_poolLimitFloorGreaterThanInitial_throws() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().vrRamp(new VrRampInput(null, null, null, null, new BigDecimal("0.60"), null, null, new BigDecimal("0.70"))).build();

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
    }

    @Test
    @DisplayName("pStepWeeks=0 — poolLimitRate 램프 비활성화, poolLimitFloor=0도 하한 검증 없이 통과")
    void reconfigure_pStepWeeksZero_disablesRampAndSkipsFloorValidation() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().vrRamp(new VrRampInput(null, null, null, null, null, 0, 0, BigDecimal.ZERO)).build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.pStepWeeks()).isEqualTo(0);
        assertThat(captured.pGraceWeeks()).isEqualTo(0);
        assertThat(captured.poolLimitFloor()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("gStepWeeks=0 — gradient 램프 비활성화, gGraceWeeks=0·gMax=0을 검증 없이 통과")
    void reconfigure_gStepWeeksZero_disablesRampAndSkipsMaxValidation() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().vrRamp(new VrRampInput(null, 0, 0, 0, null, null, null, null)).build();

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.gStepWeeks()).isEqualTo(0);
        assertThat(captured.gGraceWeeks()).isEqualTo(0);
        assertThat(captured.gMax()).isEqualTo(0);
    }

    @Test
    @DisplayName("pStepWeeks=0이어도 poolLimitFloor > initialPoolLimitRate이면 IllegalArgumentException (DB CHECK 위반으로 새는 것 방지)")
    void reconfigure_pStepWeeksZero_poolLimitFloorExceedsInitial_stillThrows() {
        stubHappyPathChain();
        // currentDetail.initialPoolLimitRate() == 0.75 (상속) — poolLimitFloor=0.90은 이를 초과
        ReconfigureVrCommand cmd = reconfigureVr().vrRamp(new VrRampInput(null, null, null, null, null, 0, 0, new BigDecimal("0.90"))).build();

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
    }

    // --- 8) 주식 주입인데 단가 누락 ---

    @Test
    @DisplayName("injectShares>0인데 injectSharePrice 누락 → IllegalArgumentException")
    void reconfigure_injectSharesWithoutPrice_throws() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().injectShares(5, (BigDecimal) null).build();

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(any(CycleSnapshotCreator.VrReconfiguration.class));
    }

    @Test
    @DisplayName("수량 주입 V 증분은 USD 센트(소수 2자리) HALF_UP으로 반올림된다")
    void reconfigure_injectShares_valueRoundedToCents() {
        // V = 1000.00 + 5 × 120.005 = 1600.025 → 1600.03
        currentPrice = new BigDecimal("120.005");
        stubHappyPathChain();
        ReconfigureVrCommand cmd = reconfigureVr().injectShares(5, "60.00").build();

        service.reconfigure(strategyId, requesterId, cmd);

        assertThat(captureReconfigureCall().newValue()).isEqualTo(new BigDecimal("1600.03"));
    }
}
