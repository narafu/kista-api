package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.CancelResult;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.trading.domain.model.ReconfigureVrCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.user.domain.model.User;
import com.kista.sharedkernel.NotificationChannel;
import com.kista.application.usecase.StrategyUseCase;
import com.kista.application.port.output.AccountPort;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyCycleVrPort;
import com.kista.application.port.output.StrategyPort;
import com.kista.application.port.output.StrategyVrDetailPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

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

// VrReconfigureService 단위 테스트 — 순수 파라미터 수정/자본 주입/램프 시계·검증/소유권 분기 검증
// package-private VrReconfigureService와 같은 패키지에 위치 (application.service.trading)
@ExtendWith(MockitoExtension.class)
@DisplayName("VrReconfigureService 단위 테스트")
class VrReconfigureServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock UserPort userPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock StrategyCycleVrPort strategyCycleVrPort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock BrokerAdapterRegistry registry;
    @Mock CycleSnapshotCreator cycleSnapshotCreator;
    @Mock OrderCancelService orderCancelService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StrategyUseCase strategyUseCase;
    @Mock BrokerPricePort pricePort; // registry.require(account, BrokerPricePort.class) 반환값

    @InjectMocks VrReconfigureService service;

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
    private StrategyDetail expectedDetail;
    private User user;
    private BigDecimal currentPrice;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        account = DomainFixtures.kisAccount(accountId, requesterId);
        vrStrategy = new Strategy(strategyId, accountId, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        currentCycle = new StrategyCycle(cycleId, strategyId, strategyVersionId,
                BigDecimal.valueOf(1000), null, LocalDate.now().minusWeeks(4), null, null, null);
        // 기본 램프: initialGradient=10/gMax=15/gGraceWeeks=gStepWeeks=52,26 / initialPoolLimitRate=0.75/floor=0.50
        currentDetail = new StrategyVrDetail(strategyVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 15, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50"));
        currentCycleVr = new StrategyCycleVrDetail(cycleId, new BigDecimal("1000.00"), 10, new BigDecimal("0.75"));
        latestPosition = new CyclePosition(UUID.randomUUID(), cycleId,
                new BigDecimal("500.00"), new BigDecimal("95.00"), new BigDecimal("50.00"), 10, null, null);
        newCycleAfterReconfigure = new StrategyCycle(UUID.randomUUID(), strategyId, UUID.randomUUID(),
                BigDecimal.valueOf(500), null, LocalDate.now(), null, null, null);
        expectedDetail = new StrategyDetail(vrStrategy, BigDecimal.ZERO, LocalDate.now(), null, false, null, null, null);
        user = DomainFixtures.activeUser(requesterId, NotificationChannel.NONE);
        currentPrice = new BigDecimal("120.00");
        // 서비스 내부와 동일한 SSOT 호출 — DstInfo.nextTradeDate()는 실제 시각 기준이라 테스트에서도 그대로 재사용
        today = com.kista.trading.domain.model.DstInfo.nextTradeDate();
    }

    // reconfigure()가 검증을 통과해 cycleSnapshotCreator.reconfigureVrCycle까지 도달하는 공통 스텁 체인.
    // 일부 테스트(램프 검증 실패)는 이 체인 중 뒷부분(findFirstByStrategyId 이후) 스텁이 실제로는 호출되지 않으므로 lenient 처리.
    private void stubHappyPathChain(LocalDate firstStartDate) {
        lenient().when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(vrStrategy);
        lenient().when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(account);
        lenient().when(registry.require(account, BrokerPricePort.class)).thenReturn(pricePort);
        lenient().when(pricePort.getPrice(Ticker.TQQQ, account)).thenReturn(currentPrice);
        lenient().when(orderCancelService.cancelByCycle(strategyId, requesterId)).thenReturn(new CancelResult(0, 0));
        lenient().when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.of(currentCycle));
        lenient().when(strategyVrDetailPort.findByStrategyVersionId(strategyVersionId)).thenReturn(Optional.of(currentDetail));
        lenient().when(strategyCycleVrPort.findByCycleId(cycleId)).thenReturn(Optional.of(currentCycleVr));
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(strategyId)).thenReturn(Optional.of(latestPosition));
        StrategyCycle firstCycle = new StrategyCycle(UUID.randomUUID(), strategyId, strategyVersionId,
                BigDecimal.valueOf(500), null, firstStartDate, null, null, null);
        lenient().when(strategyCyclePort.findFirstByStrategyId(strategyId)).thenReturn(Optional.of(firstCycle));
        lenient().when(cycleSnapshotCreator.reconfigureVrCycle(
                        any(), any(), any(), any(), any(), any(),
                        anyInt(), anyInt(), anyInt(), anyInt(),
                        any(), anyInt(), anyInt(), any(),
                        any(), any(), any(), anyLong()))
                .thenReturn(newCycleAfterReconfigure);
        lenient().when(userPort.findByIdOrThrow(requesterId)).thenReturn(user);
        lenient().when(strategyUseCase.getById(strategyId, requesterId)).thenReturn(expectedDetail);
    }

    private void stubHappyPathChain() {
        stubHappyPathChain(today.minusWeeks(10));
    }

    // cmd의 16개 필드 전부 null인 기본 커맨드 — 필요한 필드만 override해서 사용
    private ReconfigureVrCommand allNullCmd() {
        return new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    // cycleSnapshotCreator.reconfigureVrCycle(...) 호출 인자 전체를 캡처해 검증하기 위한 홀더
    private record CapturedCall(
            UUID strategyId, UUID currentCycleId, LocalDate today,
            Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount,
            Integer initialGradient, Integer gGraceWeeks, Integer gStepWeeks, Integer gMax,
            BigDecimal initialPoolLimitRate, Integer pGraceWeeks, Integer pStepWeeks, BigDecimal poolLimitFloor,
            AccountBalance postBalance, BigDecimal closingPrice, BigDecimal newValue, Long weeks) {
    }

    private CapturedCall captureReconfigureCall() {
        ArgumentCaptor<UUID> strategyIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> cycleIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<LocalDate> todayCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Integer> intervalWeeksCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<BigDecimal> bandWidthCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Integer> recurringAmountCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> initialGradientCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> gGraceWeeksCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> gStepWeeksCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> gMaxCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<BigDecimal> initialPoolLimitRateCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Integer> pGraceWeeksCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> pStepWeeksCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<BigDecimal> poolLimitFloorCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<AccountBalance> postBalanceCaptor = ArgumentCaptor.forClass(AccountBalance.class);
        ArgumentCaptor<BigDecimal> closingPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> newValueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Long> weeksCaptor = ArgumentCaptor.forClass(Long.class);

        verify(cycleSnapshotCreator).reconfigureVrCycle(
                strategyIdCaptor.capture(), cycleIdCaptor.capture(), todayCaptor.capture(),
                intervalWeeksCaptor.capture(), bandWidthCaptor.capture(), recurringAmountCaptor.capture(),
                initialGradientCaptor.capture(), gGraceWeeksCaptor.capture(), gStepWeeksCaptor.capture(), gMaxCaptor.capture(),
                initialPoolLimitRateCaptor.capture(), pGraceWeeksCaptor.capture(), pStepWeeksCaptor.capture(), poolLimitFloorCaptor.capture(),
                postBalanceCaptor.capture(), closingPriceCaptor.capture(), newValueCaptor.capture(), weeksCaptor.capture());

        return new CapturedCall(
                strategyIdCaptor.getValue(), cycleIdCaptor.getValue(), todayCaptor.getValue(),
                intervalWeeksCaptor.getValue(), bandWidthCaptor.getValue(), recurringAmountCaptor.getValue(),
                initialGradientCaptor.getValue(), gGraceWeeksCaptor.getValue(), gStepWeeksCaptor.getValue(), gMaxCaptor.getValue(),
                initialPoolLimitRateCaptor.getValue(), pGraceWeeksCaptor.getValue(), pStepWeeksCaptor.getValue(), poolLimitFloorCaptor.getValue(),
                postBalanceCaptor.getValue(), closingPriceCaptor.getValue(), newValueCaptor.getValue(), weeksCaptor.getValue());
    }

    // --- 1) 순수 파라미터 수정 (자본 주입 없음) ---

    @Test
    @DisplayName("순수 파라미터 수정: bandWidth만 변경 → V 이월, holdings/usdDeposit 불변, 나머지 램프값 상속")
    void reconfigure_pureParamChange_carriesOverBalanceAndValue() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(new BigDecimal("20.00"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        StrategyDetail result = service.reconfigure(strategyId, requesterId, cmd);

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
        // 자본 주입 없음 → holdings/usdDeposit 불변, V 이월
        assertThat(captured.postBalance().holdings()).isEqualTo(latestPosition.holdings());
        assertThat(captured.postBalance().usdDeposit()).isEqualByComparingTo(latestPosition.usdDeposit());
        assertThat(captured.newValue()).isEqualByComparingTo(currentCycleVr.value());

        verify(orderCancelService).cancelByCycle(strategyId, requesterId);
        verify(strategyUseCase).getById(strategyId, requesterId);
        assertThat(result).isSameAs(expectedDetail);
    }

    // --- 2) 수량 주입 ---

    @Test
    @DisplayName("수량 주입: holdings 증가 + 평단가 가중평균 + V += 주입수량×현재가")
    void reconfigure_injectShares_updatesHoldingsAvgPriceAndValue() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, 5, new BigDecimal("60.00"), null, null, null);

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        // newHoldings = 기존 10 + 5 = 15
        assertThat(captured.postBalance().holdings()).isEqualTo(15);
        // 가중평균: (50.00×10 + 60.00×5) / 15 = 800.00/15 = 53.3333 (scale4 HALF_UP)
        BigDecimal expectedAvgPrice = new BigDecimal("500.00").add(new BigDecimal("300.00"))
                .divide(BigDecimal.valueOf(15), 4, java.math.RoundingMode.HALF_UP);
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
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(new BigDecimal("20.00"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        StrategyDetail result = service.reconfigure(strategyId, requesterId, cmd);

        assertThat(result).isEqualTo(expectedDetail);
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent));
    }

    // --- 3) 예수금 주입 ---

    @Test
    @DisplayName("예수금 주입: usdDeposit 증가, holdings/avgPrice 불변, V 이월")
    void reconfigure_injectDeposit_updatesDepositOnly() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, new BigDecimal("200.00"), null, null);

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.postBalance().usdDeposit()).isEqualByComparingTo("700.00"); // 500.00 + 200.00
        assertThat(captured.postBalance().holdings()).isEqualTo(latestPosition.holdings());
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(latestPosition.avgPrice());
        // 수량 주입 없으므로 V 이월 (증분 없음)
        assertThat(captured.newValue()).isEqualByComparingTo(currentCycleVr.value());
    }

    // --- 3-1) 자본 인출 ---

    @Test
    @DisplayName("수량 인출: holdings 감소, 평단가는 그대로 유지, V -= 인출수량×현재가")
    void reconfigure_withdrawShares_reducesHoldingsKeepsAvgPriceReducesValue() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 4, null);

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        // newHoldings = 기존 10 - 4 = 6
        assertThat(captured.postBalance().holdings()).isEqualTo(6);
        // 인출은 잔여 수량의 원가를 바꾸지 않음 — 평단가 그대로
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(latestPosition.avgPrice());
        // V -= 4 × 120.00 = 480.00 → 1000.00 - 480.00 = 520.00
        assertThat(captured.newValue()).isEqualByComparingTo("520.00");
    }

    @Test
    @DisplayName("예수금 인출: usdDeposit 감소, holdings/avgPrice 불변, V 이월")
    void reconfigure_withdrawDeposit_reducesDepositOnly() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("150.00"));

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.postBalance().usdDeposit()).isEqualByComparingTo("350.00"); // 500.00 - 150.00
        assertThat(captured.postBalance().holdings()).isEqualTo(latestPosition.holdings());
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(latestPosition.avgPrice());
        assertThat(captured.newValue()).isEqualByComparingTo(currentCycleVr.value());
    }

    @Test
    @DisplayName("인출 주식 수가 보유 수량을 초과 → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_withdrawSharesExceedsHoldings_throws() {
        stubHappyPathChain();
        // latestPosition.holdings() == 10
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 11, null);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(
                any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(),
                any(), anyInt(), anyInt(), any(),
                any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("인출 예수금이 보유 예수금을 초과 → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_withdrawDepositExceedsBalance_throws() {
        stubHappyPathChain();
        // latestPosition.usdDeposit() == 500.00
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, new BigDecimal("500.01"));

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(
                any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(),
                any(), anyInt(), anyInt(), any(),
                any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("인출 후 주입: 잔여 수량 기준으로 평단가 가중평균 재계산")
    void reconfigure_withdrawThenInject_recomputesAvgPriceFromRemainingShares() {
        stubHappyPathChain();
        // 기존 10주(평단가 50.00) 중 4주 인출 → 잔여 6주(원가 300.00), 이후 3주를 60.00에 주입
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, 3, new BigDecimal("60.00"), null, 4, null);

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        // newHoldings = (10 - 4) + 3 = 9
        assertThat(captured.postBalance().holdings()).isEqualTo(9);
        // 가중평균: (50.00×6 + 60.00×3) / 9 = (300.00+180.00)/9 = 480.00/9
        BigDecimal expectedAvgPrice = new BigDecimal("300.00").add(new BigDecimal("180.00"))
                .divide(BigDecimal.valueOf(9), 4, java.math.RoundingMode.HALF_UP);
        assertThat(captured.postBalance().avgPrice()).isEqualByComparingTo(expectedAvgPrice);
    }

    // --- 4) 비-VR 전략 ---

    @Test
    @DisplayName("비-VR 전략 재설정 시도 → IllegalArgumentException")
    void reconfigure_nonVrStrategy_throwsIllegalArgumentException() {
        Strategy infiniteStrategy = new Strategy(strategyId, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(infiniteStrategy);
        when(accountPort.requireOwnedAccount(accountId, requesterId)).thenReturn(account);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, allNullCmd()))
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

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, allNullCmd()))
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
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, 12, null, null, null,
                null, null, null, null, null, null, null, null, null);

        service.reconfigure(strategyId, requesterId, cmd);

        CapturedCall captured = captureReconfigureCall();
        assertThat(captured.weeks()).isEqualTo(60L);
        // 사전 계산 검증: ChronoUnit.WEEKS.between(firstStart, today)와 동일한 값이어야 함
        assertThat(captured.weeks()).isEqualTo(ChronoUnit.WEEKS.between(today.minusWeeks(60), today));
        // 램프값 자체는 cmd 재지정이 정상 반영됨 (weeks 계산과는 독립적으로 갱신)
        assertThat(captured.initialGradient()).isEqualTo(12);
    }

    // --- 7) 램프 검증 실패 ---

    @Test
    @DisplayName("gMax < initialGradient → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_invalidRamp_gMaxLessThanInitialGradient_throws() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, 20, null, null, 15,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(
                any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(),
                any(), anyInt(), anyInt(), any(),
                any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("poolLimitFloor > initialPoolLimitRate → IllegalArgumentException, cycleSnapshotCreator 미호출")
    void reconfigure_invalidRamp_poolLimitFloorGreaterThanInitial_throws() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                new BigDecimal("0.60"), null, null, new BigDecimal("0.70"), null, null, null, null, null);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(
                any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(),
                any(), anyInt(), anyInt(), any(),
                any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("pStepWeeks=0 — poolLimitRate 램프 비활성화, poolLimitFloor=0도 하한 검증 없이 통과")
    void reconfigure_pStepWeeksZero_disablesRampAndSkipsFloorValidation() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, 0, 0, BigDecimal.ZERO, null, null, null, null, null);

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
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, 0, 0, 0,
                null, null, null, null, null, null, null, null, null);

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
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, 0, 0, new BigDecimal("0.90"), null, null, null, null, null);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(
                any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(),
                any(), anyInt(), anyInt(), any(),
                any(), any(), any(), anyLong());
    }

    // --- 8) 주식 주입인데 단가 누락 ---

    @Test
    @DisplayName("injectShares>0인데 injectSharePrice 누락 → IllegalArgumentException")
    void reconfigure_injectSharesWithoutPrice_throws() {
        stubHappyPathChain();
        ReconfigureVrCommand cmd = new ReconfigureVrCommand(null, null, null, null, null, null, null,
                null, null, null, null, 5, null, null, null, null);

        assertThatThrownBy(() -> service.reconfigure(strategyId, requesterId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(cycleSnapshotCreator, never()).reconfigureVrCycle(
                any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(),
                any(), anyInt(), anyInt(), any(),
                any(), any(), any(), anyLong());
    }
}
