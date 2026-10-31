package com.kista.tradingcore.trading.application.service;
import com.kista.tradingcore.trading.domain.creation.VrRampInput;
import com.kista.tradingcore.trading.domain.creation.VrRampParams;
import com.kista.tradingcore.trading.application.service.support.StrategyHistoryQueryService;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.matching.domain.model.*; import com.kista.tradingcore.trading.domain.model.*;
import com.kista.shared.kernel.RecurringMode;
import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyFieldSettings;
import com.kista.tradingcore.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort; import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.trading.domain.creation.DcaCreationResolver;
import com.kista.tradingcore.trading.domain.creation.InfiniteCreationResolver;
import com.kista.tradingcore.trading.domain.creation.PrivacyCreationResolver;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolvers;
import com.kista.tradingcore.trading.domain.creation.VrCreationResolver;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyDefaults;

@ExtendWith(MockitoExtension.class)
@DisplayName("StrategyService 단위 테스트")
class StrategyServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;       // VR 버전 상세 포트
    @Mock StrategyCycleVrPort strategyCycleVrPort;         // VR 사이클 상세 포트
    @Mock VrStrategyDetailService vrStrategyDetailService;      // trading "usecase" — buildSummary/findSummary는 포뮬러를 담고 있어 테스트에서 재구현하지 않고 직접 모킹
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock AccountPort accountPort;
    @Mock TradingUserProfilePort tradingUserProfilePort;
    @Mock MarginPort marginPort;
    @Mock BrokerPricePort brokerPricePort;                  // 중간부터 시작 — 등록 시점 시장가(전일종가) 조회
    @Mock StrategyCreationPolicyPort strategyCreationPolicyPort; // 신규 전략 생성 설정 조회
    @Mock OrderPort orderPort;                                   // 전략별 주문 내역 조회
    @Mock com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort privacyTradePort; // 시드 미리보기 PRIVACY 기준표
    @Mock StrategyDcaDetailPort dcaDetailPort;                   // DCA 버전 설정 포트
    @Mock DcaExecutionPort dcaExecutionPort;                     // DCA 회차 이력 포트
    @Mock MarketCalendarPort marketCalendarPort;                 // DCA 다음 실행일 계산용 캘린더
    @Mock OrderCancelService orderCancelService;                 // 일시중지·삭제 전 미체결 주문 정리

    private StrategyService strategyService;

    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID  = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();
    private static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();

    // ACTIVE 상태 전략 픽스처
    private static final Strategy ACTIVE_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE
    );

    // PAUSED 상태 전략 픽스처
    private static final Strategy PAUSED_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.PAUSED,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE
    );

    private static final UUID CYCLE_ID = UUID.randomUUID();

    // 현재 사이클 픽스처 (시작금액 1000)
    private static final StrategyCycle CYCLE = new StrategyCycle(
            CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"), null, LocalDate.now(), null, null, null
    );

    @BeforeEach
    void setUp() {
        DcaSummaryAssembler dcaSummaryAssembler = new DcaSummaryAssembler(marketCalendarPort);
        StrategyDetailAssembler detailAssembler = new StrategyDetailAssembler(
                strategyVersionPort, strategyInfiniteDetailPort, vrStrategyDetailService, strategyCyclePort,
                cyclePositionPort, cyclePositionInfiniteDetailPort, dcaDetailPort, dcaExecutionPort, dcaSummaryAssembler);
        StrategyCreationService creationService = new StrategyCreationService(
                strategyPort,
                accountPort,
                tradingUserProfilePort,
                brokerPricePort,
                strategyCreationPolicyPort,
                new StrategyCreationResolvers(List.of(
                        new InfiniteCreationResolver(), new PrivacyCreationResolver(), new VrCreationResolver(), new DcaCreationResolver())),
                new StrategyCreationPersister(strategyPort, strategyVersionPort, strategyInfiniteDetailPort,
                        vrStrategyDetailService, strategyCyclePort, cyclePositionPort, cyclePositionInfiniteDetailPort,
                        dcaDetailPort),
                new StrategyStartAmountCalculator(privacyTradePort),
                new com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies(List.of(
                        new com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy(null, null))),
                detailAssembler);
        var historyStrategies = new com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies(List.of(
                new com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy(null, null),
                new com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy(null)));
        StrategyHistoryQueryService historyQueryService = new StrategyHistoryQueryService(
                accountPort, strategyPort, cyclePositionPort, orderPort, historyStrategies,
                privacyTradePort, brokerPricePort,
                new com.kista.tradingcore.trading.application.service.support.UnassignedUsdCalculator(strategyPort, cyclePositionPort, marginPort, historyStrategies));
        strategyService = new StrategyService(
                strategyPort,
                strategyVersionPort,
                strategyCyclePort,
                cyclePositionPort,
                accountPort,
                creationService,
                detailAssembler,
                historyQueryService,
                new CycleSnapshotCreator(
                        strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService),
                orderCancelService,
                new StrategyStateWriter(strategyPort, strategyCyclePort, cyclePositionPort));
        lenient().when(orderCancelService.cancelOpenOrders(any(), any())).thenReturn(new CancelResult(0, 0));
        for (StrategyType type : StrategyType.values()) {
            lenient().when(strategyCreationPolicyPort.find(type)).thenReturn(Optional.of(defaultTradingSettings(type)));
        }
        lenient().when(strategyVersionPort.findActiveByStrategyId(any()))
                .thenAnswer(invocation -> Optional.of(new StrategyVersion(
                        STRATEGY_VERSION_ID, invocation.getArgument(0), 1, null, null)));
        lenient().when(strategyVersionPort.nextVersionNo(any())).thenReturn(1);
        lenient().when(strategyVersionPort.save(any()))
                .thenAnswer(invocation -> {
                    StrategyVersion version = invocation.getArgument(0);
                    return new StrategyVersion(
                            version.id() != null ? version.id() : STRATEGY_VERSION_ID,
                            version.strategyId(),
                            version.versionNo(),
                            version.createdAt(),
                            version.deletedAt());
                });
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(any()))
                .thenAnswer(invocation -> Optional.of(new StrategyInfiniteDetail(invocation.getArgument(0), 20)));
        lenient().when(strategyInfiniteDetailPort.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(cyclePositionInfiniteDetailPort.findByCyclePositionId(any())).thenReturn(Optional.empty());
        lenient().when(cyclePositionInfiniteDetailPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(strategyVrDetailPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(strategyCycleVrPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // vrStrategyDetailService(VrStrategyDetailService mock) — 순수 위임(포뮬러 없음) 4개 메서드는
        // 기존 strategyVrDetailPort/strategyCycleVrPort mock으로 그대로 전달해 그 mock들을 검증하는
        // 기존 테스트들(verify(strategyVrDetailPort)... 등)을 건드리지 않는다.
        // buildSummary()/findSummary()는 poolLimit 계산·null 처리 분기(실제 포뮬러)를 담고 있어
        // 여기서 재구현하지 않고 각 테스트가 필요할 때 직접 stub한다(미stub 시 기본값 null/Optional.empty()).
        lenient().when(vrStrategyDetailService.saveVersionDetail(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Integer recurringAmount = invocation.getArgument(3);
                    VrRampParams ramp = invocation.getArgument(4);
                    return strategyVrDetailPort.save(new StrategyVrDetail(
                            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                            recurringAmount != null ? recurringAmount : 0,
                            ramp.initialGradient(), ramp.gGraceWeeks(), ramp.gStepWeeks(), ramp.gMax(),
                            ramp.initialPoolLimitRate(), ramp.pGraceWeeks(), ramp.pStepWeeks(), ramp.poolLimitFloor()));
                });
        lenient().when(vrStrategyDetailService.saveInitialCycleDetail(any(), any(), any()))
                .thenAnswer(invocation -> {
                    BigDecimal initialValue = invocation.getArgument(1);
                    StrategyVrDetail vrDetail = invocation.getArgument(2);
                    return strategyCycleVrPort.save(new StrategyCycleVrDetail(
                            invocation.getArgument(0), initialValue != null ? initialValue : BigDecimal.ZERO,
                            vrDetail.gradientAt(0), vrDetail.poolLimitRateAt(0)));
                });
        // 등록 응답은 조회 경로(toDetail)로 조립된다 — 직전에 저장된 값을 조회 포트가 그대로 돌려주도록 맞춘다
        lenient().when(strategyCyclePort.findLatestByStrategyId(any()))
                .thenAnswer(invocation -> lastSaved(strategyCyclePort, StrategyCycle.class));
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(any()))
                .thenAnswer(invocation -> lastSaved(cyclePositionPort, CyclePosition.class));
        lenient().when(cyclePositionPort.findFirstOne(any()))
                .thenAnswer(invocation -> lastSaved(cyclePositionPort, CyclePosition.class));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(any()))
                .thenAnswer(invocation -> lastSaved(strategyInfiniteDetailPort, StrategyInfiniteDetail.class));
        lenient().when(dcaDetailPort.findActiveByStrategyId(any()))
                .thenAnswer(invocation -> mockingDetails(dcaDetailPort).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("save") && i.getArguments().length == 2
                                && i.getArgument(1) instanceof DcaStrategyConfig)
                        .reduce((first, second) -> second).map(i -> (DcaStrategyConfig) i.getArgument(1)));
        lenient().when(vrStrategyDetailService.findVrDetailsByVersionIds(any()))
                .thenAnswer(invocation -> strategyVrDetailPort.findByStrategyVersionIds(invocation.getArgument(0)));
        lenient().when(vrStrategyDetailService.findCycleVrDetailsByCycleIds(any()))
                .thenAnswer(invocation -> strategyCycleVrPort.findByCycleIds(invocation.getArgument(0)));
    }

    // mock의 save(...)에 마지막으로 넘어간 type 인자 — 조회 포트 스텁이 "방금 저장된 값"을 돌려주는 용도
    private static <T> Optional<T> lastSaved(Object mock, Class<T> type) {
        return mockingDetails(mock).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save") && i.getArguments().length == 1 && type.isInstance(i.getArgument(0)))
                .reduce((first, second) -> second).map(i -> type.cast(i.getArgument(0)));
    }

    // 등록 응답은 조회 경로의 findSummary로 조립되므로 buildSummary와 함께 stub한다
    private void stubVrSummary(VrSummary summary) {
        lenient().when(vrStrategyDetailService.buildSummary(any(), any(), any(), any())).thenReturn(summary);
        lenient().when(vrStrategyDetailService.findSummary(any(), any(), any(), any())).thenReturn(Optional.of(summary));
    }

    private Account ownerAccount() {
        return TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID);
    }

    private TradingUserProfile activeUserProfile() {
        return TradingFixtures.tradingUserProfile(USER_ID);
    }

    // RuntimeSettingsService.toTradingSettings()가 매핑을 마친 뒤 반환하는 값과 동일한 trading 소유 타입 픽스처 —
    // admin RuntimeSettings.defaults()의 전략별 기본값을 그대로 trading 타입으로 재현한다(admin 타입 비의존).
    private StrategyCreationSettings defaultTradingSettings(StrategyType type) {
        return switch (type) {
            case INFINITE -> new StrategyCreationSettings(true,
                    new StrategyFieldSettings<>(true, List.of(StrategyTicker.values()), StrategyTicker.SOXL),
                    new StrategyFieldSettings<>(true, List.of(20, 30, 40), StrategyDefaults.DEFAULT_DIVISION_COUNT),
                    null, null, null);
            case PRIVACY -> new StrategyCreationSettings(true,
                    new StrategyFieldSettings<>(false, List.of(StrategyTicker.SOXL), StrategyTicker.SOXL),
                    null, null, null, null);
            case VR -> new StrategyCreationSettings(true,
                    new StrategyFieldSettings<>(false, List.of(StrategyTicker.TQQQ), StrategyTicker.TQQQ),
                    null,
                    new StrategyFieldSettings<>(true, List.of(RecurringMode.values()), RecurringMode.HOLD),
                    new StrategyFieldSettings<>(true,
                            List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(15), BigDecimal.valueOf(20)), BigDecimal.valueOf(15)),
                    new StrategyFieldSettings<>(true, List.of(1, 2, 4), 2));
            case DCA -> new StrategyCreationSettings(false, // 이 테스트는 DCA를 다루지 않음 — 비활성 기본 정책만 제공
                    new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                    null, null, null, null);
        };
    }

    private StrategyCreationSettings disabledSettings(StrategyType type) {
        StrategyCreationSettings current = defaultTradingSettings(type);
        return new StrategyCreationSettings(false, current.ticker(), current.divisionCount(),
                current.recurringMode(), current.bandWidth(), current.intervalWeeks());
    }

    private StrategyTicker defaultTicker(StrategyType type) {
        return defaultTradingSettings(type).ticker().defaultValue();
    }

    private RegisterStrategyCommand commandFor(StrategyType type, StrategyTicker ticker, int divisionCount,
                                                Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount) {
        // 중간부터 시작 입력(initialHoldings/initialAvgPrice) 없음 — holdings=0에서 시작하는 기존 시나리오 전용 헬퍼
        return new RegisterStrategyCommand(type, ticker, null, null, divisionCount, null, null, intervalWeeks, bandWidth, recurringAmount, VrRampInput.NONE, null, null, null);
    }

    private void stubSuccessfulRegistration(StrategyType type, StrategyTicker ticker) {
        Account account = ownerAccount();
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy saved = new Strategy(strategyId, ACCOUNT_ID, type, StrategyStatus.ACTIVE, ticker,
                StrategyCycleSeedType.NONE);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any())).thenReturn(saved);
        // 저장 인자(특히 startDate)를 그대로 반영 — 고정값 반환 시 scheduledStartDate 검증이 무의미해짐
        when(strategyCyclePort.save(any())).thenAnswer(invocation -> {
            StrategyCycle arg = invocation.getArgument(0);
            return new StrategyCycle(cycleId, arg.strategyId(), arg.strategyVersionId(),
                    arg.startAmount(), arg.endAmount(), arg.startDate(), arg.endDate(),
                    arg.createdAt(), arg.deletedAt());
        });
        when(cyclePositionPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        if (type == StrategyType.VR) {
        }
    }

    @Test
    @DisplayName("pause() 호출 시 전략 상태가 PAUSED로 저장된다")
    void pause_saves_strategy_as_paused() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        strategyService.pause(STRATEGY_ID, USER_ID);

        verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.PAUSED));
    }

    @Test
    @DisplayName("pauseWithPlacedCryptoOrderIsRefused: 코인 체결 확인 중이면 PAUSED 기록 전에 거부")
    void pauseWithPlacedCryptoOrderIsRefused() {
        Account account = ownerAccount();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        doThrow(new com.kista.tradingcore.trading.domain.model.OrderCancelException("체결 확인 중인 코인 주문이 있어 잠시 후 다시 시도해 주세요."))
                .when(orderCancelService).requireNoCryptoInFlight(STRATEGY_ID, account);

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, USER_ID))
                .isInstanceOf(com.kista.tradingcore.trading.domain.model.OrderCancelException.class);

        verify(strategyPort, never()).save(any());
        verify(orderCancelService, never()).cancelOpenOrders(any(), any());
    }

    @Test
    @DisplayName("pause() 호출 시 PAUSED 기록 후 미체결 주문을 정리하고, 취소 실패가 있어도 일시중지는 유지된다")
    void pause_cancels_open_orders_and_pauses_even_when_cancel_fails() {
        Account account = ownerAccount();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(orderCancelService.cancelOpenOrders(STRATEGY_ID, account)).thenReturn(new CancelResult(1, 1));

        strategyService.pause(STRATEGY_ID, USER_ID);

        // PAUSED를 먼저 기록해 이후 배치가 새 주문을 만들지 않게 한 뒤 취소한다
        var inOrder = inOrder(strategyPort, orderCancelService);
        inOrder.verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.PAUSED));
        inOrder.verify(orderCancelService).cancelOpenOrders(STRATEGY_ID, account);
    }

    @Test
    @DisplayName("pause() 호출 시 이미 일시중지된 전략이면 주문을 건드리지 않고 IllegalStateException")
    void pause_already_paused_does_not_cancel_orders() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class);

        verify(orderCancelService, never()).cancelOpenOrders(any(), any());
    }

    @Test
    @DisplayName("delete() 호출 시 PAUSED 기록·미체결 주문 정리 후 포지션 → 사이클 → 전략 순으로 소프트 삭제한다")
    void delete_cancels_open_orders_then_soft_deletes() {
        Account account = ownerAccount();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);

        strategyService.delete(STRATEGY_ID, USER_ID);

        var inOrder = inOrder(orderCancelService, cyclePositionPort, strategyCyclePort, strategyPort);
        inOrder.verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.PAUSED));
        inOrder.verify(orderCancelService).cancelOpenOrders(STRATEGY_ID, account);
        inOrder.verify(cyclePositionPort).deleteByStrategyId(STRATEGY_ID);
        inOrder.verify(strategyCyclePort).deleteByStrategyId(STRATEGY_ID);
        inOrder.verify(strategyPort).delete(STRATEGY_ID);
    }

    @Test
    @DisplayName("delete() 호출 시 실패가 자격증명 오류뿐이면 삭제를 진행한다(재시도해도 풀리지 않음)")
    void delete_proceeds_when_only_credential_failures() {
        Account account = ownerAccount();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(orderCancelService.cancelOpenOrders(STRATEGY_ID, account)).thenReturn(new CancelResult(0, 1, 1));

        strategyService.delete(STRATEGY_ID, USER_ID);

        verify(strategyPort).delete(STRATEGY_ID);
    }

    @Test
    @DisplayName("delete() 호출 시 증권사 취소에 실패한 주문이 있으면 삭제하지 않고 PAUSED로 남긴다")
    void delete_aborts_when_order_cancel_fails() {
        Account account = ownerAccount();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(orderCancelService.cancelOpenOrders(STRATEGY_ID, account)).thenReturn(new CancelResult(0, 1));

        assertThatThrownBy(() -> strategyService.delete(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class);

        verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.PAUSED));
        verify(strategyPort, never()).delete(any());
        verify(strategyCyclePort, never()).deleteByStrategyId(any());
        verify(cyclePositionPort, never()).deleteByStrategyId(any());
    }

    @Test
    @DisplayName("resume() 호출 시 전략 상태가 ACTIVE로 저장된다")
    void resume_saves_strategy_as_active() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        strategyService.resume(STRATEGY_ID, USER_ID);

        verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.ACTIVE));
    }

    @Test
    @DisplayName("resume() 호출 시 최신 사이클이 종료됐으면(endDate 존재) endAmount를 시드로 새 사이클을 재오픈한다")
    void resume_reopens_ended_cycle_with_end_amount_as_seed() {
        StrategyCycle endedCycle = new StrategyCycle(
                CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"), new BigDecimal("874.50"),
                LocalDate.now().minusDays(5), LocalDate.now(), null, null);
        CyclePosition lastPosition = CyclePosition.tradeSnapshot(CYCLE_ID,
                new AccountBalance(0, null, new BigDecimal("874.50")), new BigDecimal("103.20"));

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(endedCycle));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(lastPosition));
        when(strategyCyclePort.save(any(StrategyCycle.class)))
                .thenAnswer(invocation -> {
                    StrategyCycle c = invocation.getArgument(0);
                    return new StrategyCycle(UUID.randomUUID(), c.strategyId(), c.strategyVersionId(),
                            c.startAmount(), c.endAmount(), c.startDate(), c.endDate(), null, null);
                });

        strategyService.resume(STRATEGY_ID, USER_ID);

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());
        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo("874.50");
        verify(cyclePositionPort).save(argThat(p ->
                p.usdDeposit().compareTo(new BigDecimal("874.50")) == 0 && p.holdings().signum() == 0));
        verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.ACTIVE));
    }

    private void stubEndedCycleResume(String endAmount, String closingPrice) {
        StrategyCycle endedCycle = new StrategyCycle(
                CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"), new BigDecimal(endAmount),
                LocalDate.now().minusDays(5), LocalDate.now(), null, null);
        CyclePosition lastPosition = CyclePosition.tradeSnapshot(CYCLE_ID,
                new AccountBalance(0, null, new BigDecimal(endAmount)),
                closingPrice == null ? null : new BigDecimal(closingPrice));
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(endedCycle));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(lastPosition));
    }

    @Test
    @DisplayName("resume() — 종료 사이클 최종 금액이 1주 가격 미만이면 재개를 거부한다")
    void resume_rejects_when_end_amount_below_one_share() {
        stubEndedCycleResume("12.34", "60.00");

        assertThatThrownBy(() -> strategyService.resume(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("남은 원장($12.34)이 1주 가격($60.00)보다 작아 재개할 수 없습니다. 전략을 삭제한 뒤 충분한 투자금으로 다시 등록해 주세요.");
        verify(strategyCyclePort, never()).save(any());
        verify(cyclePositionPort, never()).save(any());
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("resume() — 종가 정보가 없으면 기존대로 재오픈하고 ACTIVE로 저장한다")
    void resume_reopens_when_closing_price_missing() {
        stubEndedCycleResume("12.34", null);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenAnswer(i -> i.getArgument(0));

        strategyService.resume(STRATEGY_ID, USER_ID);

        verify(strategyCyclePort).save(any(StrategyCycle.class));
        verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.ACTIVE));
    }

    @Test
    @DisplayName("resume() 호출 시 최신 사이클이 아직 진행 중이면(endDate 없음) 사이클을 재오픈하지 않는다")
    void resume_does_not_reopen_cycle_when_still_open() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE)); // endDate=null

        strategyService.resume(STRATEGY_ID, USER_ID);

        verify(strategyCyclePort, never()).save(any());
        verify(cyclePositionPort, never()).save(any());
        verify(strategyPort).save(argThat(s -> s.status() == StrategyStatus.ACTIVE));
    }

    @Test
    @DisplayName("pause() 호출 시 소유자가 아니면 SecurityException이 발생한다 (→ 403)")
    void pause_by_non_owner_throws_security_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, otherUserId))
                .isInstanceOf(SecurityException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("resume() 호출 시 소유자가 아니면 SecurityException이 발생한다 (→ 403)")
    void resume_by_non_owner_throws_security_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> strategyService.resume(STRATEGY_ID, otherUserId))
                .isInstanceOf(SecurityException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("pause() 호출 시 비소유자가 이미 일시중지된 전략을 요청해도 소유권 오류가 상태 오류보다 먼저 발생한다 (→ 403, 400 아님)")
    void pause_by_non_owner_on_already_paused_strategy_throws_security_exception_not_state_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, otherUserId))
                .isInstanceOf(SecurityException.class)
                .isNotInstanceOf(IllegalStateException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("resume() 호출 시 비소유자가 이미 활성화된 전략을 요청해도 소유권 오류가 상태 오류보다 먼저 발생한다 (→ 403, 400 아님)")
    void resume_by_non_owner_on_already_active_strategy_throws_security_exception_not_state_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> strategyService.resume(STRATEGY_ID, otherUserId))
                .isInstanceOf(SecurityException.class)
                .isNotInstanceOf(IllegalStateException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("update() 호출 시 newSeed가 null이면 cycleSeedType만 변경되고 시드는 변경되지 않는다")
    void update_without_newSeed_only_changes_cycleSeedType() {
        Strategy maintained = ACTIVE_STRATEGY.withCycleSeedType(StrategyCycleSeedType.MAINTAIN);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(maintained);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));

        StrategyDetail result = strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(StrategyCycleSeedType.MAINTAIN, null));

        assertThat(result.strategy().cycleSeedType()).isEqualTo(StrategyCycleSeedType.MAINTAIN);
        assertThat(result.initialUsdDeposit()).isEqualTo(new BigDecimal("1000"));
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateCycleStartSnapshot(any(), any());
    }

    @Test
    @DisplayName("update() holdings가 있으면 시드 수정을 거절한다")
    void update_seed_with_holdings_throws() {
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), new BigDecimal("110"), new BigDecimal("100"), BigDecimal.valueOf(10), null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(CYCLE);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(latest));

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(null, new BigDecimal("5000"))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateCycleStartSnapshot(any(), any());
    }

    @Test
    @DisplayName("update() holdings가 0이면 strategy_cycle과 cycle_position 시작점을 함께 갱신한다")
    void update_seed_change_with_no_holdings() {
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000"), new BigDecimal("110"), null, BigDecimal.valueOf(0), null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(CYCLE);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(latest));
        when(cyclePositionInfiniteDetailPort.findByCyclePositionId(latest.id())).thenReturn(Optional.empty());

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("3000")));

        verify(strategyCyclePort).updateStartAmount(CYCLE_ID, new BigDecimal("3000"));
        verify(cyclePositionPort).updateCycleStartSnapshot(STRATEGY_ID, new BigDecimal("3000"));
    }

    @Test
    @DisplayName("update() 시드가 0 이하이면 IllegalArgumentException 발생")
    void update_seed_zero_or_negative_throws() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(null, BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).save(any());
    }

    @Test
    @DisplayName("update() VR 시드 수정은 저장 전에 거절하고 VR 재설정을 안내한다")
    void update_vrSeed_throwsBeforePersistenceMutation() {
        Strategy vrStrategy = new Strategy(
                STRATEGY_ID, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        CyclePosition opening = new CyclePosition(
                UUID.randomUUID(), CYCLE_ID, new BigDecimal("1000.00"),
                null, null, BigDecimal.valueOf(0), null, null);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(vrStrategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        lenient().when(strategyPort.save(any(Strategy.class))).thenReturn(vrStrategy);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(opening));

        assertThatThrownBy(() -> strategyService.update(
                STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("3000.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VR 재설정");

        verify(strategyPort, never()).save(any());
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateCycleStartSnapshot(any(), any());
    }

    @Test
    @DisplayName("update() VR의 연속 사이클 정책을 NONE 외 값으로 바꾸면 저장 전에 거절한다")
    void update_vrCycleSeedType_nonNone_throws() {
        Strategy vrStrategy = new Strategy(
                STRATEGY_ID, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(vrStrategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.update(
                STRATEGY_ID, USER_ID, new UpdateStrategyCommand(StrategyCycleSeedType.MAINTAIN, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("연속 사이클 정책");

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("update() 호출 시 소유자가 아니면 SecurityException이 발생한다 (→ 403)")
    void update_by_non_owner_throws_security_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, otherUserId,
                new UpdateStrategyCommand(null, new BigDecimal("3000"))))
                .isInstanceOf(SecurityException.class);

        verify(strategyPort, never()).save(any());
    }

    // --- listByUserId() ---

    @Test
    @DisplayName("listByUserId() 호출 시 해당 사용자의 전 계좌 전략을 합쳐 반환한다")
    void listByUserId_aggregatesStrategiesAcrossAccounts() {
        UUID accountAId = UUID.randomUUID();
        UUID accountBId = UUID.randomUUID();
        Account accountA = new Account(accountAId, USER_ID, "계좌A", "11111111", "k", "s", null, Broker.KIS, null, null);
        Account accountB = new Account(accountBId, USER_ID, "계좌B", "22222222", "k", "s", null, Broker.KIS, null, null);

        UUID strategyAId = UUID.randomUUID();
        UUID strategyBId = UUID.randomUUID();
        Strategy strategyA = new Strategy(strategyAId, accountAId, StrategyType.INFINITE, StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Strategy strategyB = new Strategy(strategyBId, accountBId, StrategyType.INFINITE, StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(accountA, accountB));
        when(strategyPort.findByAccountIds(List.of(accountAId, accountBId)))
                .thenReturn(Map.of(accountAId, List.of(strategyA), accountBId, List.of(strategyB)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyAId, strategyBId)))
                .thenReturn(Map.of(strategyAId, CYCLE, strategyBId, CYCLE));

        List<StrategyDetail> result = strategyService.listByUserId(USER_ID);

        assertThat(result).extracting(d -> d.strategy().id())
                .containsExactlyInAnyOrder(strategyAId, strategyBId);
    }

    // --- register() ---

    @Test
    @DisplayName("register()는 비활성화된 각 전략 유형의 신규 생성을 거부한다")
    void register_disabledStrategyType_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        for (StrategyType type : StrategyType.values()) {
            when(strategyCreationPolicyPort.find(type)).thenReturn(Optional.of(disabledSettings(type)));
            RegisterStrategyCommand cmd = commandFor(type, defaultTicker(type), 20, 2, new BigDecimal("15"), 0);

            assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("비활성화");
        }

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register()는 계좌 소유 검증을 한 번만 하고, 응답은 같은 stub의 getById 결과와 같다")
    void register_requiresOwnedAccountOnce() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);

        StrategyDetail registered = strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, null, 0, null, null, null));

        verify(accountPort, times(1)).requireOwnedAccount(ACCOUNT_ID, USER_ID);
        when(strategyPort.findByIdOrThrow(registered.strategy().id())).thenReturn(registered.strategy());
        assertThat(strategyService.getById(registered.strategy().id(), USER_ID)).isEqualTo(registered);
    }

    @Test
    @DisplayName("INFINITE register()는 생략한 ticker와 divisionCount에 런타임 기본값을 적용한다")
    void register_infinite_appliesOmittedDefaults() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.TQQQ);
        StrategyCreationSettings customized = new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ), StrategyTicker.TQQQ),
                new StrategyFieldSettings<>(true, List.of(30, 40), 30), null, null, null);
        when(strategyCreationPolicyPort.find(StrategyType.INFINITE)).thenReturn(Optional.of(customized));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, null, 0, null, null, null));

        assertThat(result.strategy().ticker()).isEqualTo(StrategyTicker.TQQQ);
        assertThat(result.divisionCount()).isEqualTo(30);
        verify(strategyInfiniteDetailPort).save(argThat(detail -> detail.divisionCount() == 30));
    }

    @Test
    @DisplayName("INFINITE register()는 ticker와 divisionCount 허용 목록 밖의 값을 거부한다")
    void register_infinite_rejectsDisallowedFields() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings infinite = defaultTradingSettings(StrategyType.INFINITE);
        StrategyCreationSettings restricted = new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.SOXL), StrategyTicker.SOXL),
                infinite.divisionCount(), null, null, null);
        when(strategyCreationPolicyPort.find(StrategyType.INFINITE)).thenReturn(Optional.of(restricted));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.MAGX, 20, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, 25, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("INFINITE 고정 ticker와 divisionCount는 명시적 기본값만 허용한다")
    void register_infinite_nonCustomizableFields_allowDefaultRejectOther() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings fixed = new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(false, List.of(StrategyTicker.SOXL), StrategyTicker.SOXL),
                new StrategyFieldSettings<>(false, List.of(30), 30), null, null, null);
        when(strategyCreationPolicyPort.find(StrategyType.INFINITE)).thenReturn(Optional.of(fixed));
        when(strategyPort.findByAccountIdAndTicker(any(), any())).thenReturn(List.of(ACTIVE_STRATEGY));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, 30, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 해당 종목");
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.TQQQ, 30, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, 40, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("INFINITE register()는 기본 설정의 divisionCount 30과 40을 계속 허용한다")
    void register_infinite_preservesDivisionCountThirtyAndForty() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(any(), any())).thenReturn(List.of(ACTIVE_STRATEGY));

        for (int divisionCount : List.of(30, 40)) {
            assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                    commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, divisionCount, null, null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("이미 해당 종목");
        }
    }

    @Test
    @DisplayName("PRIVACY와 VR register()는 고정 ticker와 다른 명시 입력을 거부한다")
    void register_fixedTickerTypes_rejectExplicitNonDefault() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.PRIVACY, StrategyTicker.TQQQ, 20, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, StrategyTicker.SOXL, 20, 2, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VR register()는 생략한 ticker, recurring mode, bandWidth, intervalWeeks 기본값을 적용한다")
    void register_vr_appliesOmittedDefaults() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID,
                new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 0, null, null, null, null, null, VrRampInput.NONE, null, null, null));

        assertThat(result.strategy().ticker()).isEqualTo(StrategyTicker.TQQQ);
        verify(strategyVrDetailPort).save(argThat(detail -> detail.intervalWeeks() == 2
                && detail.bandWidth().compareTo(new BigDecimal("15")) == 0
                && detail.recurringAmount() == 0));
    }

    @Test
    @DisplayName("VR register()는 bandWidth와 intervalWeeks 허용 목록 밖의 값을 거부한다")
    void register_vr_rejectsDisallowedBandWidthAndInterval() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 20, 2, new BigDecimal("12"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 20, 3, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VR 고정 bandWidth와 intervalWeeks는 명시적 기본값만 허용한다")
    void register_vr_nonCustomizableBandAndInterval_allowDefaultRejectOther() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings vr = defaultTradingSettings(StrategyType.VR);
        StrategyCreationSettings fixed = new StrategyCreationSettings(true, vr.ticker(), null, vr.recurringMode(),
                new StrategyFieldSettings<>(false, List.of(new BigDecimal("15")), new BigDecimal("15")),
                new StrategyFieldSettings<>(false, List.of(2), 2));
        when(strategyCreationPolicyPort.find(StrategyType.VR)).thenReturn(Optional.of(fixed));
        when(strategyPort.findByAccountIdAndTicker(any(), any())).thenReturn(List.of(ACTIVE_STRATEGY));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 0, 2, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 해당 종목");
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 0, 2, new BigDecimal("10"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 0, 4, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VR recurringMode 허용 목록은 recurringAmount 부호로 검증하고 금액 크기는 제한하지 않는다")
    void register_vr_validatesRecurringModeBySignOnly() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings vr = defaultTradingSettings(StrategyType.VR);
        StrategyCreationSettings depositOnly = new StrategyCreationSettings(true, vr.ticker(), null,
                new StrategyFieldSettings<>(true, List.of(RecurringMode.DEPOSIT), RecurringMode.DEPOSIT),
                vr.bandWidth(), vr.intervalWeeks());
        when(strategyCreationPolicyPort.find(StrategyType.VR)).thenReturn(Optional.of(depositOnly));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 20, 2, new BigDecimal("15"), -999)))
                .isInstanceOf(IllegalArgumentException.class);
        when(strategyPort.findByAccountIdAndTicker(any(), any())).thenReturn(List.of(ACTIVE_STRATEGY));
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 20, 2, new BigDecimal("15"), 999)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 해당 종목");
    }

    @Test
    @DisplayName("VR recurringMode가 고정이면 생략/0은 허용하고 명시적 nonzero는 거부한다")
    void register_vr_nonCustomizableRecurringMode_forcesHold() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings vr = defaultTradingSettings(StrategyType.VR);
        StrategyCreationSettings holdOnly = new StrategyCreationSettings(true, vr.ticker(), null,
                new StrategyFieldSettings<>(false, List.of(RecurringMode.HOLD), RecurringMode.HOLD),
                vr.bandWidth(), vr.intervalWeeks());
        when(strategyCreationPolicyPort.find(StrategyType.VR)).thenReturn(Optional.of(holdOnly));
        when(strategyPort.findByAccountIdAndTicker(any(), any())).thenReturn(List.of(ACTIVE_STRATEGY));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 20, 2, new BigDecimal("15"), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 20, 2, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.VR, null, 20, 2, new BigDecimal("15"), 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update()는 신규 생성 설정을 조회하지 않는다")
    void update_doesNotLoadCreationSettings() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, null));

        verifyNoInteractions(strategyCreationPolicyPort);
    }

    @Test
    @DisplayName("register() 호출 시 같은 계좌에 동일 종목 전략이 이미 있으면 IllegalStateException 발생")
    void register_duplicateTicker_throws() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, null, null, 20, null, null, null, null, null, VrRampInput.NONE, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.SOXL)).thenReturn(List.of(ACTIVE_STRATEGY));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalStateException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register() 호출 시 시드가 가용 예수금을 넘어도 등록은 성공하고 증권사 잔고를 조회하지 않는다")
    void registerOverFreeCashIsAllowed() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.TQQQ, new BigDecimal("600"), null, 20, null, null, null, null, null, VrRampInput.NONE, null, null, null);
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.TQQQ);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.initialUsdDeposit()).isEqualByComparingTo("600");
        verifyNoInteractions(marginPort);
    }

    // --- VR register() ---

    @Test
    @DisplayName("VR register() holdings=0이어도 초기 V 입력이 있으면 그 값을 V값으로 저장한다")
    void register_vr_explicitInitialValue_holdingsZero_usesOverrideAsValue() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, BigDecimal.ZERO, null, 20, null, null, 2, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, new BigDecimal("5000"), null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                BigDecimal.ZERO, null, null, BigDecimal.valueOf(0), null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // holdings=0이라 실제 포지션·startAmount는 그대로 0이지만, V값 저장은 override(5000)를 사용한다
        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(new BigDecimal("5000")) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().signum() == 0));
        verifyNoInteractions(brokerPricePort);
    }

    @Test
    @DisplayName("VR register() 초기 V 입력이 있어도 실제 포지션·startAmount는 시장가 평가금 기준을 그대로 유지한다")
    void register_vr_explicitInitialValue_withHoldings_keepsEvaluatedAssetsSeparate() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, BigDecimal.ZERO, null, 20, BigDecimal.valueOf(10), new BigDecimal("100.00"), 2, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, new BigDecimal("99999"), null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(StrategyTicker.TQQQ, account.toBrokerRef())).thenReturn(new BigDecimal("50.00"));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(cyclePositionPort.save(any(CyclePosition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // V값은 override(99999) 저장
        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(new BigDecimal("99999")) == 0));
        // 실제 포지션(holdings=10, avgPrice=100)과 startAmount(전일종가 50×10=500.00)는 override와 무관
        verify(cyclePositionPort).save(argThat(p ->
                p.holdings().compareTo(BigDecimal.valueOf(10)) == 0 && p.avgPrice().compareTo(new BigDecimal("100.00")) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("500.00")) == 0));
    }

    @Test
    @DisplayName("VR register() 인출식 최소자산 검증은 초기 V 입력을 반영하지 않고 실제 평가금 기준으로만 판단한다")
    void register_vr_explicitInitialValue_doesNotBypassWithdrawalMinimumCheck() {
        // 게이트(V+예수금>0)는 override(100000)로 통과하지만, 인출식 최소자산(실제 평가금 0 + 예수금 100)은 미달
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("100"), null, 20, null, null, 2, new BigDecimal("15.00"), -100, VrRampInput.NONE, null, new BigDecimal("100000"), null);
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인출식 VR 전략의 초기 자산");
    }

    @Test
    @DisplayName("VR register() 초기 V값이 음수이면 IllegalArgumentException")
    void register_vr_negativeInitialValue_throws() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, null, null, 20, null, null, 2, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, new BigDecimal("-1"), null);
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값(initialVrValue)은 0 이상이어야 합니다");
    }

    @Test
    @DisplayName("VR register() 성공 — StrategyVrDetail·StrategyCycleVrDetail 저장, poolLimit 계산, cycleSeedType NONE 강제")
    void register_vr_success_savesVrDetailsAndPoolLimit() {
        // holdings=0(중간부터 시작 미입력) → V=0, 초기 자산 2000(예수금만), poolLimitRate=0.75(recurringAmount=0) → poolLimit = 1500.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("2000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("2000"), null, null, BigDecimal.valueOf(0), null, null);
        // recurringAmount=0 → 기본 poolLimitRate=0.75 (거치식) — poolLimit 달러 파생은 개장 pool(2000)×0.75=1500.00
        StrategyCycleVrDetail savedCycleVr = new StrategyCycleVrDetail(
                vrCycleId, BigDecimal.ZERO, 10, new BigDecimal("0.75"));

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class))).thenReturn(savedCycleVr);
        // poolLimit = 개장 pool(2000) × poolLimitRate(0.75) = 1500.00 (hand-computed, VrStrategyDetailService 공식과 동일)
        stubVrSummary(
                new VrSummary(BigDecimal.ZERO, new BigDecimal("15.00"), 4, 0,
                        new BigDecimal("1500.00"), new BigDecimal("2000"), new BigDecimal("0.75"), 10,
                        10, 52, 26, 20, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50")));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // cycleSeedType NONE 강제 + VR → TQQQ 강제 검증
        verify(strategyPort).save(argThat(s ->
                s.type() == StrategyType.VR && s.ticker() == StrategyTicker.TQQQ
                        && s.cycleSeedType() == StrategyCycleSeedType.NONE));
        // StrategyVrDetail 저장 검증
        verify(strategyVrDetailPort).save(argThat(d ->
                d.intervalWeeks() == 4
                        && d.bandWidth().compareTo(new BigDecimal("15.00")) == 0
                        && d.recurringAmount() == 0));
        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimitRate().compareTo(new BigDecimal("0.75")) == 0
                        && cv.gradient() == 10));
        // 등록 시점 중간부터 시작 미입력이면 시장가 조회를 건너뛴다
        verifyNoInteractions(brokerPricePort);
        // 응답 VrSummary 검증
        assertThat(result.vr()).isNotNull();
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("1500.00");
        assertThat(result.divisionCount()).isNull();  // VR은 divisionCount 없음
        assertThat(result.currentRound()).isNull();    // VR은 currentRound 없음
    }

    @Test
    @DisplayName("VR register() holdings=0 허용 — 중간부터 시작 미입력 시 initialSnapshot으로 초기 포지션 저장")
    void register_vr_holdingsZero_allowed() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 2, new BigDecimal("10.00"), 0, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("1000"), null, null, BigDecimal.valueOf(0), null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        // holdings=0이어도 예외 없이 등록 성공
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.currentHoldings()).isEqualTo(0);
        // holdings=0 → CyclePosition.initialSnapshot(closingPrice도 null) 사용 검증, 시장가 조회는 건너뛴다
        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(vrCycleId) && p.holdings().signum() == 0
                        && p.avgPrice() == null && p.closingPrice() == null));
        verifyNoInteractions(brokerPricePort);
    }

    @Test
    @DisplayName("VR 적립식은 초기 V와 초기 시드가 모두 0이어도 등록 가능")
    void register_vr_recurringDeposit_allowsZeroInitialValueAndSeed() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, BigDecimal.ZERO, null, 20, null, null, 2, new BigDecimal("15.00"), 200, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                BigDecimal.ZERO, null, null, BigDecimal.valueOf(0), null, null);
        // recurringAmount=200(적립식) → 기본 poolLimitRate=1.0, 단 개장 pool=0이므로 파생 poolLimit은 0.00
        StrategyCycleVrDetail savedCycleVr = new StrategyCycleVrDetail(
                vrCycleId, BigDecimal.ZERO, 10, BigDecimal.ONE);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class))).thenReturn(savedCycleVr);
        // 개장 pool=0 → poolLimit = 0 × 1.0 = 0.00 (hand-computed, VrStrategyDetailService 공식과 동일)
        stubVrSummary(
                new VrSummary(BigDecimal.ZERO, new BigDecimal("15.00"), 2, 200,
                        new BigDecimal("0.00"), BigDecimal.ZERO, BigDecimal.ONE, 10,
                        10, 52, 26, 20, BigDecimal.ONE, 52, 26, new BigDecimal("0.50")));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.vr()).isNotNull();
        assertThat(result.vr().value()).isEqualByComparingTo("0");
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("VR 거치식과 인출식은 초기 V와 초기 시드가 모두 0이면 등록 불가")
    void register_vr_nonDeposit_requiresInitialValueOrSeed() {
        RegisterStrategyCommand hold = new RegisterStrategyCommand(StrategyType.VR, null, BigDecimal.ZERO, null, 20, null, null, 2, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, null, null);
        RegisterStrategyCommand withdraw = new RegisterStrategyCommand(StrategyType.VR, null, BigDecimal.ZERO, null, 20, null, null, 2, new BigDecimal("15.00"), -100, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        // 검증 순서상 잔고 검증(validateBalanceIfRequired)이 VR 파라미터 검증보다 먼저 실행된다
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, hold))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, withdraw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
    }

    @Test
    @DisplayName("VR 인출식은 초기 자산이 월 인출액의 100배 이상이어야 등록 가능")
    void register_vr_withdrawal_requiresMinimumInitialAssets() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 2, new BigDecimal("15.00"), -100, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        // 검증 순서상 잔고 검증(validateBalanceIfRequired)이 VR 파라미터 검증보다 먼저 실행된다
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인출식 VR 전략의 초기 자산");
    }

    @Test
    @DisplayName("VR register() recurringAmount null이면 0으로 저장한다")
    void register_vr_nullRecurringAmount_defaultsToZero() {
        // holdings=0 → V=0, 초기 자산 2000(예수금만), poolLimitRate=0.75 → poolLimit = 1500.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("2000"), null, 20, null, null, 4, new BigDecimal("15.00"), null, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("2000"), null, null, BigDecimal.valueOf(0), null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        // poolLimit = 개장 pool(2000) × poolLimitRate(0.75) = 1500.00 (hand-computed, VrStrategyDetailService 공식과 동일)
        stubVrSummary(
                new VrSummary(BigDecimal.ZERO, new BigDecimal("15.00"), 4, 0,
                        new BigDecimal("1500.00"), new BigDecimal("2000"), new BigDecimal("0.75"), 10,
                        10, 52, 26, 20, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50")));
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d -> d.recurringAmount() == 0));
        // recurringAmount=0(거치식) → 기본 poolLimitRate=0.75, poolLimit 달러 파생값 = 개장 pool(2000)×0.75 = 1500.00
        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimitRate().compareTo(new BigDecimal("0.75")) == 0
                        && cv.gradient() == 10));
        assertThat(result.vr().recurringAmount()).isZero();
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("VR register() 적립식 initialValue null이면 0으로 정규화한다")
    void register_vr_recurringDeposit_nullInitialValue_defaultsToZero() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, BigDecimal.ZERO, null, 20, null, null, 4, new BigDecimal("15.00"), 200, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenAnswer(inv -> inv.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() 적립식 initialUsdDeposit null이면 0으로 정규화한다")
    void register_vr_recurringDeposit_nullPool_defaultsToZero() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, null, null, 20, null, null, 4, new BigDecimal("15.00"), 200, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenAnswer(inv -> inv.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // recurringAmount=200(적립식) → 기본 poolLimitRate=1.0 (개장 pool=0이라 달러 파생 poolLimit은 0이지만 저장 비율 자체는 1.0)
        verify(strategyCycleVrPort).save(argThat(cv -> cv.poolLimitRate().compareTo(BigDecimal.ONE) == 0));
        verify(cyclePositionPort).save(argThat(p -> p.usdDeposit().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() poolLimit scale=2 HALF_UP — recurringAmount > 0 시 poolLimitRate=1.0")
    void register_vr_poolLimitRate_withDeposit() {
        // holdings=0 → V=0, recurringAmount=100(입금) → poolLimitRate=1.0 → poolLimit = 1000 × 1.0 = 1000.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 100, VrRampInput.NONE, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("1000"), null, null, BigDecimal.valueOf(0), null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        // poolLimit = 개장 pool(1000) × poolLimitRate(1.0) = 1000.00 (hand-computed, VrStrategyDetailService 공식과 동일)
        stubVrSummary(
                new VrSummary(BigDecimal.ZERO, new BigDecimal("15.00"), 4, 100,
                        new BigDecimal("1000.00"), new BigDecimal("1000"), BigDecimal.ONE, 10,
                        10, 52, 26, 20, BigDecimal.ONE, 52, 26, new BigDecimal("0.50")));
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimitRate().compareTo(BigDecimal.ONE) == 0
                        && cv.gradient() == 10));
        // poolLimit(달러) 파생값 검증 — 개장 pool(1000) × poolLimitRate(1.0) = 1000.00, scale=2 HALF_UP
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("1000.00");
    }

    // --- VR register() 램프 파라미터 정규화·검증 (경과주수 기반 램프 신규 기능) ---

    @Test
    @DisplayName("VR register() 램프 8필드 모두 생략 — 거치식(recurringAmount=0) 부호 파생 기본값 저장")
    void register_vr_rampFieldsOmitted_appliesNormalizedDefaults() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, null, null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // recurringAmount=0(거치식) → initialGradient=10, gMax=20, initialPoolLimitRate=0.75, poolLimitFloor=0.5 (RAMP_DEFAULTS_BY_MODE와 동기화)
        verify(strategyVrDetailPort).save(argThat(d ->
                d.initialGradient() == 10 && d.gGraceWeeks() == 52 && d.gStepWeeks() == 26 && d.gMax() == 20
                        && d.initialPoolLimitRate().compareTo(new BigDecimal("0.75")) == 0
                        && d.pGraceWeeks() == 52 && d.pStepWeeks() == 26
                        && d.poolLimitFloor().compareTo(new BigDecimal("0.50")) == 0));
    }

    @Test
    @DisplayName("VR register() 램프 8필드 모두 생략 — 인출식(recurringAmount<0)은 initialGradient=40/gMax=50/initialPoolLimitRate=0.1 기본값 적용")
    void register_vr_rampFieldsOmitted_withdrawal_appliesGradient40() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);
        // 인출식: recurringAmount=-100, intervalWeeks=2 → 필요 최소자산 = 100×100×4/2 = 20000, initialUsdDeposit=20000으로 충족
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("20000"), null, 20, null, null, 2, new BigDecimal("15.00"), -100, VrRampInput.NONE, null, null, null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.initialGradient() == 40 && d.gMax() == 50
                        && d.initialPoolLimitRate().compareTo(new BigDecimal("0.1")) == 0
                        && d.poolLimitFloor().compareTo(new BigDecimal("0.1")) == 0));
    }

    @Test
    @DisplayName("VR register() 램프 8필드 명시 입력 시 정규화 없이 그대로 저장된다")
    void register_vr_rampFieldsExplicit_savedAsProvided() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, new VrRampInput(12, 10, 5, 18, new BigDecimal("0.60"), 8, 4, new BigDecimal("0.30")), null, null, null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.initialGradient() == 12 && d.gGraceWeeks() == 10 && d.gStepWeeks() == 5 && d.gMax() == 18
                        && d.initialPoolLimitRate().compareTo(new BigDecimal("0.60")) == 0
                        && d.pGraceWeeks() == 8 && d.pStepWeeks() == 4
                        && d.poolLimitFloor().compareTo(new BigDecimal("0.30")) == 0));
    }

    @Test
    @DisplayName("VR register() pStepWeeks=0 — poolLimitRate 램프 비활성화, poolLimitFloor=0도 그대로 저장(0 초과 요구만 생략)")
    void register_vr_pStepWeeksZero_disablesRampAndSkipsFloorValidation() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, new VrRampInput(null, null, null, null, new BigDecimal("0.60"), 0, 0, BigDecimal.ZERO), null, null, null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.pStepWeeks() == 0 && d.pGraceWeeks() == 0
                        && d.poolLimitFloor().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() gStepWeeks=0 — gradient 램프 비활성화, gGraceWeeks=0·gMax=0을 그대로 저장")
    void register_vr_gStepWeeksZero_disablesRampAndSkipsMaxValidation() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, new VrRampInput(10, 0, 0, 0, null, null, null, null), null, null, null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.gStepWeeks() == 0 && d.gGraceWeeks() == 0 && d.gMax() == 0));
    }

    @Test
    @DisplayName("VR register() pStepWeeks=0이어도 poolLimitFloor > initialPoolLimitRate이면 IllegalArgumentException (DB CHECK 위반으로 새는 것 방지)")
    void register_vr_pStepWeeksZero_poolLimitFloorExceedsInitial_stillThrows() {
        Account account = ownerAccount();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, new VrRampInput(null, null, null, null, new BigDecimal("0.60"), 0, 0, new BigDecimal("0.90")), null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolLimitFloor");

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("VR register() gMax < initialGradient이면 IllegalArgumentException")
    void register_vr_gMaxLessThanInitialGradient_throws() {
        Account account = ownerAccount();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, new VrRampInput(15, null, null, 10, null, null, null, null), null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gMax");

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("VR register() poolLimitFloor > initialPoolLimitRate이면 IllegalArgumentException")
    void register_vr_poolLimitFloorGreaterThanInitial_throws() {
        Account account = ownerAccount();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, new VrRampInput(null, null, null, null, new BigDecimal("0.50"), null, null, new BigDecimal("0.80")), null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolLimitFloor");

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("toDetail() VR 전략 — VrSummary 조립, divisionCount=null, currentRound=null")
    void toDetail_vr_assemblesVrSummary() {
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        UUID vrVersionId = UUID.randomUUID();
        Strategy vrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(vrCycleId, vrStrategyId, vrVersionId,
                new BigDecimal("1600"), null, LocalDate.now(), null, null, null);
        CyclePosition latestPos = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("700"), null, null, BigDecimal.valueOf(0), null, null);
        CyclePosition openingPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("1000"), null, null, BigDecimal.valueOf(0), null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(vrVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        // 총 시작금액 1600과 개장 USD pool 1000은 분리된다. poolLimit은 1000 × 0.50 = 500.00이다.
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                vrCycleId, new BigDecimal("3000"), 10, new BigDecimal("0.50"));

        StrategyVersion vrVersion = new StrategyVersion(vrVersionId, vrStrategyId, 1, null, null);

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(vrStrategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(vrStrategyId))).thenReturn(Map.of(vrStrategyId, vrCycle));
        when(cyclePositionPort.findLatestByCycleIds(List.of(vrCycleId))).thenReturn(Map.of(vrCycleId, latestPos));
        when(cyclePositionPort.findFirstByCycleIds(List.of(vrCycleId))).thenReturn(Map.of(vrCycleId, openingPosition));
        when(strategyVersionPort.findActiveByStrategyIds(List.of(vrStrategyId))).thenReturn(Map.of(vrStrategyId, vrVersion));
        when(strategyVrDetailPort.findByStrategyVersionIds(List.of(vrVersionId))).thenReturn(Map.of(vrVersionId, vrDetail));
        when(strategyCycleVrPort.findByCycleIds(List.of(vrCycleId))).thenReturn(Map.of(vrCycleId, cycleVr));
        // poolLimit = 개장 pool(1000) × poolLimitRate(0.50) = 500.00 (hand-computed, VrStrategyDetailService 공식과 동일)
        stubVrSummary(
                new VrSummary(new BigDecimal("3000"), new BigDecimal("15.00"), 4, 0,
                        new BigDecimal("500.00"), new BigDecimal("700"), new BigDecimal("0.50"), 10,
                        10, 52, 26, 20, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50")));

        List<StrategyDetail> result = strategyService.listByUserId(USER_ID);

        assertThat(result).hasSize(1);
        StrategyDetail detail = result.getFirst();
        assertThat(detail.divisionCount()).isNull();
        assertThat(detail.currentRound()).isNull();
        // toDetail()이 조립하는 startDate는 findLatestByStrategyId로 조회한 사이클의 startDate와 일치해야 함
        assertThat(detail.startDate()).isEqualTo(vrCycle.startDate());
        assertThat(detail.initialUsdDeposit()).isEqualByComparingTo("1000.00");
        assertThat(detail.vr()).isNotNull();
        assertThat(detail.vr().intervalWeeks()).isEqualTo(4);
        assertThat(detail.vr().bandWidth()).isEqualByComparingTo("15.00");
        assertThat(detail.vr().poolLimit()).isEqualByComparingTo("500.00");
        assertThat(detail.vr().poolLimitRate()).isEqualByComparingTo("0.50");
        assertThat(detail.vr().gradient()).isEqualTo(10);
    }

    @Test
    @DisplayName("toDetail() VR 개장 포지션이 없으면 잘못된 저장 상태로 즉시 실패한다")
    void toDetail_vrMissingOpeningPosition_throwsIllegalState() {
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy vrStrategy = new Strategy(
                vrStrategyId, ACCOUNT_ID, StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(
                vrCycleId, vrStrategyId, UUID.randomUUID(), new BigDecimal("1600.00"),
                null, LocalDate.now(), null, null, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(vrStrategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(vrStrategyId))).thenReturn(Map.of(vrStrategyId, vrCycle));
        // cyclePositionPort.findFirstByCycleIds는 stub하지 않음 — 빈 Map 기본값으로 개장 포지션 누락을 재현

        assertThatThrownBy(() -> strategyService.listByUserId(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션 없음")
                .hasMessageContaining(vrCycleId.toString());
    }

    // --- 단건↔배치 동등성 (toDetail vs toDetails) — 목록 조립 리팩토링이 단건 응답과 동일 결과를 내는지 검증 ---

    @Test
    @DisplayName("동등성: INFINITE(리버스모드 ON, divisionCount 커스텀) — getById와 listByUserId 결과가 같다")
    void equivalence_infiniteWithReverseModeAndCustomDivisionCount() {
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, ACCOUNT_ID, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, versionId,
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition position = new CyclePosition(positionId, cycleId,
                new BigDecimal("500.00"), null, new BigDecimal("20.00"), BigDecimal.valueOf(10), null, null);
        StrategyVersion version = new StrategyVersion(versionId, strategyId, 1, null, null);
        StrategyInfiniteDetail infiniteDetail = new StrategyInfiniteDetail(versionId, 30);
        CyclePositionInfiniteDetail reverseDetail = new CyclePositionInfiniteDetail(positionId, true);

        // 단건 경로(getById) stub
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.of(cycle));
        when(strategyInfiniteDetailPort.findActiveByStrategyId(strategyId)).thenReturn(Optional.of(infiniteDetail));
        when(cyclePositionPort.findLatestOneByStrategyId(strategyId)).thenReturn(Optional.of(position));
        when(cyclePositionInfiniteDetailPort.findByCyclePositionId(positionId)).thenReturn(Optional.of(reverseDetail));

        StrategyDetail single = strategyService.getById(strategyId, USER_ID);

        // 배치 경로(listByUserId) stub — 동일 데이터를 배치 메서드로 제공
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, cycle));
        when(cyclePositionPort.findLatestByCycleIds(List.of(cycleId))).thenReturn(Map.of(cycleId, position));
        when(strategyVersionPort.findActiveByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, version));
        when(strategyInfiniteDetailPort.findByStrategyVersionIds(List.of(versionId))).thenReturn(Map.of(versionId, infiniteDetail));
        when(cyclePositionInfiniteDetailPort.findByCyclePositionIds(List.of(positionId))).thenReturn(Map.of(positionId, reverseDetail));

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID);

        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst()).isEqualTo(single);
        assertThat(single.divisionCount()).isEqualTo(30);
        assertThat(single.isReverseMode()).isTrue();
    }

    @Test
    @DisplayName("동등성: VR 정상 — getById와 listByUserId 결과가 같다")
    void equivalence_vrNormal() {
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, versionId,
                new BigDecimal("1600.00"), null, LocalDate.now(), null, null, null);
        CyclePosition openingPosition = new CyclePosition(UUID.randomUUID(), cycleId,
                new BigDecimal("1000.00"), null, null, BigDecimal.valueOf(0), null, null);
        StrategyVersion version = new StrategyVersion(versionId, strategyId, 1, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(cycleId, new BigDecimal("3000.00"), 10, new BigDecimal("0.50"));

        // 단건 경로와 배치 경로가 동일한 VrSummary를 조립해야 동등성 검증이 성립 — 두 경로 모두 같은 fixture를 stub
        VrSummary vrSummaryFixture = new VrSummary(new BigDecimal("3000.00"), new BigDecimal("15.00"), 4, 0,
                new BigDecimal("500.00"), null, new BigDecimal("0.50"), 10,
                10, 52, 26, 20, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        when(vrStrategyDetailService.findSummary(any(), any(), any(), any())).thenReturn(Optional.of(vrSummaryFixture));
        stubVrSummary(vrSummaryFixture);

        // 단건 경로
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.of(cycle));
        when(cyclePositionPort.findFirstOne(cycleId)).thenReturn(Optional.of(openingPosition));
        when(cyclePositionPort.findLatestOneByStrategyId(strategyId)).thenReturn(Optional.empty());

        StrategyDetail single = strategyService.getById(strategyId, USER_ID);

        // 배치 경로
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, cycle));
        when(cyclePositionPort.findFirstByCycleIds(List.of(cycleId))).thenReturn(Map.of(cycleId, openingPosition));
        when(strategyVersionPort.findActiveByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, version));
        when(strategyVrDetailPort.findByStrategyVersionIds(List.of(versionId))).thenReturn(Map.of(versionId, vrDetail));
        when(strategyCycleVrPort.findByCycleIds(List.of(cycleId))).thenReturn(Map.of(cycleId, cycleVr));

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID);

        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst()).isEqualTo(single);
        assertThat(single.vr()).isNotNull();
        assertThat(single.divisionCount()).isNull();
        assertThat(single.currentRound()).isNull();
    }

    @Test
    @DisplayName("동등성: PRIVACY — getById와 listByUserId 결과가 같다")
    void equivalence_privacy() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, ACCOUNT_ID, StrategyType.PRIVACY,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000.00"), null, LocalDate.now(), null, null, null);
        CyclePosition position = new CyclePosition(positionId, cycleId,
                new BigDecimal("1500.00"), null, new BigDecimal("30.00"), BigDecimal.valueOf(15), null, null);

        // 단건 경로
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.of(cycle));
        when(cyclePositionPort.findLatestOneByStrategyId(strategyId)).thenReturn(Optional.of(position));

        StrategyDetail single = strategyService.getById(strategyId, USER_ID);

        // 배치 경로
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, cycle));
        when(cyclePositionPort.findLatestByCycleIds(List.of(cycleId))).thenReturn(Map.of(cycleId, position));

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID);

        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst()).isEqualTo(single);
        assertThat(single.divisionCount()).isNull();
        assertThat(single.vr()).isNull();
    }

    @Test
    @DisplayName("동등성: 사이클 없는 신규 전략 — getById와 listByUserId 결과가 같다 (모두 null 필드)")
    void equivalence_newStrategyWithoutCycle() {
        UUID strategyId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, ACCOUNT_ID, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

        // 단건 경로 — 사이클 없음(Optional.empty), 다른 stub 없이도 lenient 기본값(빈 Optional/Map)으로 처리됨
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.empty());
        when(strategyInfiniteDetailPort.findActiveByStrategyId(strategyId)).thenReturn(Optional.empty());
        when(cyclePositionPort.findLatestOneByStrategyId(strategyId)).thenReturn(Optional.empty());

        StrategyDetail single = strategyService.getById(strategyId, USER_ID);

        // 배치 경로 — 빈 Map 기본값(Mockito 기본 응답)에 의존
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID);

        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst()).isEqualTo(single);
        assertThat(single.startDate()).isNull();
        assertThat(single.initialUsdDeposit()).isNull();
        assertThat(single.divisionCount()).isEqualTo(StrategyDefaults.DEFAULT_DIVISION_COUNT);
        assertThat(single.currentRound()).isNull();
        assertThat(single.currentHoldings()).isNull();
    }

    @Test
    @DisplayName("동등성: VR 개장 포지션 없음 — getById와 listByUserId 모두 동일한 IllegalStateException을 던진다")
    void equivalence_vrMissingOpeningPosition_bothThrowSameException() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, UUID.randomUUID(),
                new BigDecimal("1600.00"), null, LocalDate.now(), null, null, null);

        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.of(cycle));
        // cyclePositionPort.findFirstOne은 stub하지 않음 — Optional.empty() 기본값으로 개장 포지션 누락 재현

        assertThatThrownBy(() -> strategyService.getById(strategyId, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션 없음")
                .hasMessageContaining(cycleId.toString());

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, cycle));
        // cyclePositionPort.findFirstByCycleIds도 stub하지 않음 — 빈 Map 기본값

        assertThatThrownBy(() -> strategyService.listByUserId(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션 없음")
                .hasMessageContaining(cycleId.toString());
    }

    @Test
    @DisplayName("N+1 재발 방지: listByUserId는 전략 수와 무관하게 단건 조회 port 메서드를 호출하지 않는다")
    void listByUserId_neverCallsSingleItemPortMethods() {
        UUID strategyAId = UUID.randomUUID();
        UUID strategyBId = UUID.randomUUID();
        UUID cycleAId = UUID.randomUUID();
        UUID cycleBId = UUID.randomUUID();
        Strategy strategyA = new Strategy(strategyAId, ACCOUNT_ID, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Strategy strategyB = new Strategy(strategyBId, ACCOUNT_ID, StrategyType.PRIVACY,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle cycleA = new StrategyCycle(cycleAId, strategyAId, STRATEGY_VERSION_ID,
                new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
        StrategyCycle cycleB = new StrategyCycle(cycleBId, strategyBId, STRATEGY_VERSION_ID,
                new BigDecimal("2000.00"), null, LocalDate.now(), null, null, null);

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID)))
                .thenReturn(Map.of(ACCOUNT_ID, List.of(strategyA, strategyB)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyAId, strategyBId)))
                .thenReturn(Map.of(strategyAId, cycleA, strategyBId, cycleB));

        strategyService.listByUserId(USER_ID);

        verify(strategyPort, never()).findByAccountId(any());
        verify(strategyCyclePort, never()).findLatestByStrategyId(any());
        verify(cyclePositionPort, never()).findLatestOneByStrategyId(any());
        verify(cyclePositionPort, never()).findFirstOne(any());
        verify(strategyVersionPort, never()).findActiveByStrategyId(any());
        verify(strategyInfiniteDetailPort, never()).findActiveByStrategyId(any());
        verify(strategyVrDetailPort, never()).findActiveByStrategyId(any());
        verify(strategyCycleVrPort, never()).findByCycleId(any());
        verify(cyclePositionInfiniteDetailPort, never()).findByCyclePositionId(any());
    }

    // --- register() 중간부터 시작 (bootstrap position) ---

    @Test
    @DisplayName("INFINITE register() 중간부터 시작 — 시장가 조회로 startAmount·초기 포지션을 holdings/avgPrice로 seed한다")
    void register_infinite_bootstrap_seedsPositionFromMarketPrice() {
        Account account = ownerAccount();
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy saved = new Strategy(strategyId, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(cycleId, strategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1500.00"), null, LocalDate.now(), null, null, null);

        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, new BigDecimal("1500"), null, 20, BigDecimal.valueOf(10), new BigDecimal("45.50"), null, null, null, VrRampInput.NONE, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(StrategyTicker.SOXL, account.toBrokerRef())).thenReturn(new BigDecimal("50.00"));
        when(strategyPort.save(any())).thenReturn(saved);
        when(strategyCyclePort.save(any())).thenReturn(savedCycle);
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // startAmount = 현금 1500 + 시장가 50.00×10 = 2000.00 (최소 시드 50×20×2 = 2000 충족)
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("2000.00")) == 0));
        verify(cyclePositionPort).save(argThat(p ->
                p.holdings().compareTo(BigDecimal.valueOf(10)) == 0
                        && p.avgPrice().compareTo(new BigDecimal("45.50")) == 0
                        && p.closingPrice().compareTo(new BigDecimal("50.00")) == 0
                        && p.usdDeposit().compareTo(new BigDecimal("1500")) == 0));
        verify(cyclePositionInfiniteDetailPort).save(argThat(d -> !d.isReverseMode()));
        assertThat(result.currentHoldings()).isEqualTo(10);
        assertThat(result.currentRound()).isNotNull(); // 등록 응답도 조회 경로와 같이 중간 시작 회차를 계산한다
    }

    @Test
    @DisplayName("PRIVACY register() 중간부터 시작 — CyclePositionInfiniteDetail을 저장하지 않는다")
    void register_privacy_bootstrap_savesPositionWithoutInfiniteDetail() {
        Account account = ownerAccount();
        StrategyTicker ticker = defaultTicker(StrategyType.PRIVACY);
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy saved = new Strategy(strategyId, ACCOUNT_ID, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                ticker, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(cycleId, strategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2200.00"), null, LocalDate.now(), null, null, null);

        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.PRIVACY, null, new BigDecimal("200"), null, 0, BigDecimal.valueOf(20), new BigDecimal("95.00"), null, null, null, VrRampInput.NONE, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(ticker, account.toBrokerRef())).thenReturn(new BigDecimal("100.00"));
        // FIDA 원가 자산 = 1,000.03 + (−0.03) = 1,000.00, 사용자 원가 자산 = 200 + 20×95.05 = 2,101 → 2.10배
        // 2.10 × 1,000.03 = 2,100.063 → CEILING 2,100.07 (HALF_UP 2,100.06이면 매매 시 FLOOR 역산이 2.09배)
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.of(new com.kista.tradingcore.privacy.domain.model.PrivacyCurrentBase(
                StrategyTicker.SOXL, new BigDecimal("1000.03"), null, new BigDecimal("-0.03"), new BigDecimal("95.05"))));
        when(strategyPort.save(any())).thenReturn(saved);
        when(strategyCyclePort.save(any())).thenReturn(savedCycle);
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(cyclePositionPort).save(argThat(p ->
                p.holdings().compareTo(BigDecimal.valueOf(20)) == 0
                        && p.avgPrice().compareTo(new BigDecimal("95.00")) == 0
                        && p.closingPrice().compareTo(new BigDecimal("100.00")) == 0));
        // 시작금액은 시장가 평가(200 + 20×100 = 2,200)가 아니라 FIDA 기준 배수 환산값
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("2100.07")) == 0));
        verify(cyclePositionInfiniteDetailPort, never()).save(any());
        assertThat(result.currentHoldings()).isEqualTo(20);
        assertThat(result.divisionCount()).isNull(); // 조회 경로와 동일 — PRIVACY는 분할 수를 싣지 않는다
    }

    @Test
    @DisplayName("PRIVACY register() 중간부터 시작 — FIDA 기준표가 없으면 IllegalArgumentException")
    void register_privacy_bootstrap_withoutBase_throws() {
        Account account = ownerAccount();
        StrategyTicker ticker = defaultTicker(StrategyType.PRIVACY);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.PRIVACY, null, new BigDecimal("200"), null, 0, BigDecimal.valueOf(20), new BigDecimal("95.00"), null, null, null, VrRampInput.NONE, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(ticker, account.toBrokerRef())).thenReturn(new BigDecimal("100.00"));
        when(privacyTradePort.findSeedPreviewBase()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("VR register() 중간부터 시작 — V값을 시장가×보유수량으로 계산한다 (live 잔고 조회 제거)")
    void register_vr_bootstrap_computesValueFromMarketPrice() {
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, BigDecimal.valueOf(5), new BigDecimal("100"), 4, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(StrategyTicker.TQQQ, account.toBrokerRef())).thenReturn(new BigDecimal("120"));
        when(strategyPort.save(any())).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenAnswer(invocation -> {
            StrategyCycle cycle = invocation.getArgument(0);
            return new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                    cycle.startAmount(), null, cycle.startDate(), null, null, null);
        });
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // poolLimit = 개장 pool(1000) × poolLimitRate(0.75) = 750.00 (hand-computed, VrStrategyDetailService 공식과 동일)
        stubVrSummary(
                new VrSummary(new BigDecimal("600.00"), new BigDecimal("15.00"), 4, 0,
                        new BigDecimal("750.00"), new BigDecimal("1000"), new BigDecimal("0.75"), 10,
                        10, 52, 26, 20, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50")));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // startAmount = USD pool 1000 + 시장가 120 × 보유수량 5 = 1600.00, V = 600.00
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("1600.00")) == 0));
        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(new BigDecimal("600.00")) == 0));
        assertThat(result.initialUsdDeposit()).isEqualByComparingTo("1000.00");
        assertThat(result.vr().value()).isEqualByComparingTo("600.00");
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("750.00");
        // VR live 잔고 조회는 완전히 제거됨 — BrokerPricePort 시장가 조회 1회로만 V가 계산된다
        verify(brokerPricePort, times(1)).getPrevClose(any(), eq(account.toBrokerRef()));
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — holdings>0인데 avgPrice가 없으면 IllegalArgumentException")
    void register_bootstrap_holdingsWithoutAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, null, null, 20, BigDecimal.valueOf(10), null, null, null, null, VrRampInput.NONE, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — avgPrice가 0이어도 holdings>0이면 IllegalArgumentException")
    void register_bootstrap_holdingsWithZeroAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, null, null, 20, BigDecimal.valueOf(10), BigDecimal.ZERO, null, null, null, VrRampInput.NONE, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — holdings가 음수면 IllegalArgumentException")
    void register_bootstrap_negativeHoldings_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, null, null, 20, BigDecimal.valueOf(-1), null, null, null, null, VrRampInput.NONE, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — avgPrice가 음수면 IllegalArgumentException")
    void register_bootstrap_negativeAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, null, null, 20, null, new BigDecimal("-5"), null, null, null, VrRampInput.NONE, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 — 시장가(전일종가) 조회 실패 시 IllegalStateException으로 등록 자체가 실패한다")
    void register_bootstrap_priceFetchFailure_throwsIllegalState() {
        Account account = ownerAccount();
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, null, null, 20, BigDecimal.valueOf(10), new BigDecimal("45.50"), null, null, null, VrRampInput.NONE, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(StrategyTicker.SOXL, account.toBrokerRef()))
                .thenThrow(new RuntimeException("증권사 API 조회 실패"));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalStateException.class);
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register() holdings=0 하위호환 — 중간부터 시작 미입력 시 포지션은 시장가 없이 기존 initialSnapshot으로 등록된다")
    void register_holdingsZero_backwardCompatible_skipsMarketPriceFetch() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, 20, null, null, null));

        assertThat(result.currentHoldings()).isEqualTo(0);
        // INFINITE는 최소 시드 검증용 전일종가만 조회할 수 있다 — 포지션 closingPrice에는 쓰지 않는다
        verify(cyclePositionPort).save(argThat(p -> p.closingPrice() == null && p.avgPrice() == null));
    }

    // --- register() INFINITE 최소 시드 검증 ---

    private RegisterStrategyCommand infiniteSeedCommand(String deposit) {
        return new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, new BigDecimal(deposit), null, 20, null, null, null, null, null, VrRampInput.NONE, null, null, null);
    }

    @Test
    @DisplayName("INFINITE register() 시작 금액이 최소 시드(전일종가×분할×2) 미만이면 400으로 거부하고 저장하지 않는다")
    void register_infinite_rejects_seed_below_min() {
        Account account = ownerAccount(); // 거부 경로는 저장 stub이 불필요해 헬퍼를 쓰지 않는다
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenReturn(new BigDecimal("60"));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, infiniteSeedCommand("2000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("최소 투자금 $2,400.00 이상이 필요합니다.");

        verifyNoInteractions(strategyVersionPort, strategyCyclePort, cyclePositionPort);
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("INFINITE register() 시작 금액이 최소 시드와 같으면 등록된다")
    void register_infinite_accepts_seed_at_min() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenReturn(new BigDecimal("60"));

        strategyService.register(USER_ID, ACCOUNT_ID, infiniteSeedCommand("2400"));

        verify(strategyPort).save(any());
    }

    @Test
    @DisplayName("INFINITE register() 전일종가 조회가 실패하면 최소 시드 검증만 건너뛰고 등록된다")
    void register_infinite_skips_min_check_when_price_fetch_fails() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenThrow(new RuntimeException("시세 장애"));

        strategyService.register(USER_ID, ACCOUNT_ID, infiniteSeedCommand("100"));

        verify(strategyPort).save(any());
    }

    @Test
    @DisplayName("VR register() 신규 등록(보유 0)은 전일종가를 조회하지 않는다")
    void register_vr_does_not_fetch_prev_close() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);

        strategyService.register(USER_ID, ACCOUNT_ID, new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("2000"), null, 20, null, null, 4, new BigDecimal("15.00"), 0, VrRampInput.NONE, null, null, null));

        verify(brokerPricePort, never()).getPrevClose(any(), any());
    }

    // --- register() 시작예정일(scheduledStartDate) ---

    @Test
    @DisplayName("INFINITE register() scheduledStartDate 명시 시 저장되는 StrategyCycle.startDate가 그 값과 일치한다")
    void register_infinite_withScheduledStartDate_setsCycleStartDate() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);
        LocalDate scheduledStart = LocalDate.now(TimeZones.KST).plusDays(3);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.INFINITE, StrategyTicker.SOXL, null, null, 20, null, null, null, null, null, VrRampInput.NONE, scheduledStart, null, null);

        StrategyDetail detail = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        ArgumentCaptor<StrategyCycle> captor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(captor.capture());
        assertThat(captor.getValue().startDate()).isEqualTo(scheduledStart);
        // register() 반환 StrategyDetail의 startDate도 저장된 사이클 시작일과 일치해야 함
        assertThat(detail.startDate()).isEqualTo(scheduledStart);
    }

    @Test
    @DisplayName("PRIVACY register() scheduledStartDate 생략(null) 시 저장되는 StrategyCycle.startDate는 오늘(KST)이다")
    void register_privacy_withoutScheduledStartDate_defaultsToToday() {
        StrategyTicker ticker = defaultTicker(StrategyType.PRIVACY);
        stubSuccessfulRegistration(StrategyType.PRIVACY, ticker);

        // commandFor()는 scheduledStartDate 자리에 null을 전달 — 생략 시나리오
        strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.PRIVACY, null, 0, null, null, null));

        ArgumentCaptor<StrategyCycle> captor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(captor.capture());
        assertThat(captor.getValue().startDate()).isEqualTo(LocalDate.now(TimeZones.KST));
    }

    @Test
    @DisplayName("VR register() scheduledStartDate가 과거 날짜이면 IllegalArgumentException 발생")
    void register_vr_withPastScheduledStartDate_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        LocalDate yesterday = LocalDate.now(TimeZones.KST).minusDays(1);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, new BigDecimal("1000"), null, 20, null, null, 2, new BigDecimal("15.00"), 0, VrRampInput.NONE, yesterday, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작예정일");

        verify(strategyPort, never()).save(any());
    }

    // ── DCA 등록·조회 ──────────────────────────────────────────────

    static final String DCA_KEY = "11111111-1111-1111-1111-111111111111";

    // 등록 요청 규칙(ruleKey 없음 — 서버가 UUID 부여)
    private static DcaStrategyConfig dcaConfig(OrderDirection direction) {
        return TradingFixtures.dcaConfig(TradingFixtures.stockDcaRule(null, direction, "300", OrderType.MOC));
    }

    private static RegisterStrategyCommand dcaCommand(StrategyTicker ticker, DcaStrategyConfig dca) {
        return dcaCommand(ticker, dca, null, null);
    }

    private static RegisterStrategyCommand dcaCommand(StrategyTicker ticker, DcaStrategyConfig dca, BigDecimal holdings, BigDecimal avgPrice) {
        return new RegisterStrategyCommand(StrategyType.DCA, ticker, null, null, 0, holdings, avgPrice, null, null, null, VrRampInput.NONE, null, null, dca);
    }

    // 저장된 설정 캡처 — ruleKey가 부여됐는지 확인하고, 키를 지운 값으로 요청과 비교한다
    private DcaStrategyConfig capturedSavedConfig() {
        ArgumentCaptor<DcaStrategyConfig> captor = ArgumentCaptor.forClass(DcaStrategyConfig.class);
        verify(dcaDetailPort).save(eq(STRATEGY_VERSION_ID), captor.capture());
        assertThat(captor.getValue().rules()).allSatisfy(r -> assertThat(r.ruleKey()).hasSize(36));
        return new DcaStrategyConfig(captor.getValue().rules().stream().map(r -> r.withRuleKey(null)).toList());
    }

    private static Strategy dcaStrategy(UUID id, StrategyTicker ticker) {
        return new Strategy(id, ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE, ticker, StrategyCycleSeedType.NONE);
    }

    // DCA 등록 성공 경로 공통 stub — 잔고·시세 검증을 건너뛰므로 사용자 프로필·마진·시세 stub은 없다(strict stubs)
    private void stubDcaRegistration(StrategyTicker ticker) {
        UUID cycleId = UUID.randomUUID();
lenient().when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any())).thenReturn(dcaStrategy(UUID.randomUUID(), ticker));
        when(strategyCyclePort.save(any())).thenAnswer(inv -> {
            StrategyCycle arg = inv.getArgument(0);
            return new StrategyCycle(cycleId, arg.strategyId(), arg.strategyVersionId(), arg.startAmount(),
                    arg.endAmount(), arg.startDate(), arg.endDate(), arg.createdAt(), arg.deletedAt());
        });
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
    }

    // 주식 DCA 다음 실행일 계산용 캘린더 stub — 코인 경로는 캘린더를 조회하지 않는다
    private void stubUsCalendar() {
        when(marketCalendarPort.usSessionDateOf(any())).thenAnswer(inv -> ((LocalDate) inv.getArgument(0)).minusDays(1));
        when(marketCalendarPort.kstTradeDateOf(any())).thenAnswer(inv -> ((LocalDate) inv.getArgument(0)).plusDays(1));
        when(marketCalendarPort.isUsSessionOpen(any())).thenReturn(true);
    }

    private static DcaStrategyConfig cryptoDcaConfig() {
        return TradingFixtures.dcaConfig(TradingFixtures.cryptoDcaRule(null, OrderDirection.BUY, "10000", 9));
    }

    private void stubDcaPolicyWithCrypto() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD, StrategyTicker.BTC, StrategyTicker.ETH),
                        StrategyTicker.UPRO), null, null, null, null)));
    }

    @Test
    @DisplayName("주식 계좌에 코인 종목 DCA는 시장 불일치로 거부한다")
    void register_dca_rejectsTickerMarketMismatch() {
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.BTC, cryptoDcaConfig())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("시장");
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("주식 계좌에 코인 종목은 시장 불일치로 거부된다 — 중간부터 시작 소수 보유보다 시장 검증이 먼저")
    void register_btcOnKisAccount_rejectedAsMarketMismatch() {
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));

        // 시장 검증이 BootstrapPosition보다 늦으면 "정수여야" 문구가 먼저 나온다
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.BTC, cryptoDcaConfig(), new BigDecimal("0.5"), new BigDecimal("100"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("계좌와 종목의 시장이 다릅니다: BTC");
    }

    @Test
    @DisplayName("코인 계좌는 DCA 외 전략을 등록할 수 없다")
    void register_cryptoAccount_rejectsNonDca() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));

        // 정책 조회보다 먼저 거부하므로 정책 stub 없이도 코인 전용 규칙 메시지에 도달한다
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, 20, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("코인 계좌는 DCA 전략만 등록할 수 있습니다.");
        verify(strategyPort, never()).save(any());
        verifyNoInteractions(strategyCreationPolicyPort);
    }

    @Test
    @DisplayName("코인 계좌 DCA는 MARKET·원 단위 정수 금액만 허용한다")
    void register_cryptoDca_rejectsStockOrderType() {
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.BTC, dcaConfig(OrderDirection.BUY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MARKET");
    }

    @Test
    @DisplayName("코인 계좌 BTC DCA는 실행 시각과 함께 저장된다")
    void register_cryptoDca_succeeds() {
        stubDcaRegistration(StrategyTicker.BTC);
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.BTC)).thenReturn(List.of());

        StrategyDetail detail = strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.BTC, cryptoDcaConfig()));

        assertThat(capturedSavedConfig()).isEqualTo(cryptoDcaConfig());
        // 코인 DCA는 소수 원장이라 정수 보유 수량을 내보내지 않는다
        assertThat(detail.currentHoldings()).isNull();
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("DCA 등록은 규칙에 ruleKey를 부여해 저장하고 요약도 부여된 키로 응답한다")
    void register_dca_assignsRuleKeys() {
        stubDcaRegistration(StrategyTicker.UPRO);
        stubUsCalendar();
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of());

        StrategyDetail detail = strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.SELL)));

        assertThat(capturedSavedConfig()).isEqualTo(dcaConfig(OrderDirection.SELL));
        assertThat(detail.strategy().type()).isEqualTo(StrategyType.DCA);
        assertThat(detail.dca().rules()).singleElement().satisfies(r -> {
            assertThat(r.rule().ruleKey()).hasSize(36);
            assertThat(r.nextKstTradeDate()).isNotNull();
        });
        assertThat(detail.initialUsdDeposit()).isEqualByComparingTo("0");
        // DCA는 잔고 검증·시세 조회를 하지 않는다
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("DCA 등록 요청에 ruleKey가 있으면 존재하지 않는 규칙으로 거부한다")
    void register_dca_rejectsClientRuleKey() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO,
                TradingFixtures.dcaConfig(TradingFixtures.stockDcaRule(DCA_KEY, OrderDirection.BUY, "300", OrderType.MOC)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("존재하지 않는 규칙입니다");
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("주식 DCA 정수 초기보유는 시작 원장(예수금 0)과 초기 투입 원가 start_amount로 시작한다")
    void register_stockDca_bootstrapsLedger() {
        stubDcaRegistration(StrategyTicker.UPRO);
        stubUsCalendar();
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of());

        StrategyDetail detail = strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.BUY), new BigDecimal("10"), new BigDecimal("40")));

        assertThat(detail.currentHoldings()).isEqualTo(10);
        assertThat(detail.initialUsdDeposit()).isEqualByComparingTo("400"); // 조회 경로와 동일 — 사이클 시작금액(보유 10 × 평단 40)
        verify(cyclePositionPort).save(argThat(p -> p.holdings().compareTo(BigDecimal.TEN) == 0
                && p.usdDeposit().signum() == 0 && p.avgPrice().compareTo(new BigDecimal("40")) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("400")) == 0));
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("크립토 DCA 소수 초기보유로 시작 원장을 만든다")
    void 크립토_DCA_소수_초기보유로_시작원장_생성() {
        stubDcaRegistration(StrategyTicker.BTC);
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.BTC)).thenReturn(List.of());

        StrategyDetail detail = strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.BTC, cryptoDcaConfig(), new BigDecimal("0.01234567"), new BigDecimal("90000000")));

        assertThat(detail.currentHoldings()).isNull();
        verify(cyclePositionPort).save(argThat(p -> p.holdings().compareTo(new BigDecimal("0.01234567")) == 0
                && p.usdDeposit().signum() == 0 && p.closingPrice() == null));
        // 초기 투입 원가 = 0.01234567 × 90,000,000 = 1,111,110.30
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("1111110.30")) == 0));
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("주식 DCA 소수 초기보유는 400")
    void 주식_소수_초기보유는_400() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.BUY), new BigDecimal("1.5"), new BigDecimal("40"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정수");
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("주식 초기보유가 int 범위를 넘으면 저장 전에 400")
    void 주식_int_범위_초과_초기보유는_저장_전_400() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.BUY),
                BigDecimal.valueOf(Integer.MAX_VALUE).add(BigDecimal.ONE), new BigDecimal("40"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("허용 범위");
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("DCA도 같은 계좌·종목 두 번째 등록은 방향과 무관하게 거부한다")
    void DCA도_같은_계좌_종목_두번째_등록은_거부() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO))
                .thenReturn(List.of(dcaStrategy(UUID.randomUUID(), StrategyTicker.UPRO)));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.SELL))))
                .isInstanceOf(DuplicateStrategyTickerException.class)
                .hasMessage("같은 계좌에 이미 해당 종목 전략이 있습니다.");
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("DCA 등록도 같은 종목에 비DCA 전략이 있으면 기존 중복 메시지로 거부한다")
    void register_dca_rejectsWhenNonDcaExists() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of(ACTIVE_STRATEGY));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.SELL))))
                .isInstanceOf(DuplicateStrategyTickerException.class)
                .hasMessage("같은 계좌에 이미 해당 종목 전략이 있습니다.");
    }

    @Test
    @DisplayName("비DCA 등록은 같은 종목에 DCA가 있어도 거부한다")
    void register_nonDca_rejectsWhenDcaExists() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.SOXL))
                .thenReturn(List.of(dcaStrategy(UUID.randomUUID(), StrategyTicker.SOXL)));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, 20, null, null, null)))
                .isInstanceOf(DuplicateStrategyTickerException.class)
                .hasMessage("같은 계좌에 이미 해당 종목 전략이 있습니다.");
    }

    @Test
    @DisplayName("DCA 등록은 dca 설정이 없으면 거부한다")
    void register_dca_requiresConfig() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DCA 전략 조회는 활성 설정·최근 회차·다음 실행일을 싣는다")
    void getById_dca_includesSummary() {
        Strategy dca = dcaStrategy(STRATEGY_ID, StrategyTicker.UPRO);
        DcaExecution last = DcaExecution.skipped(STRATEGY_ID, DCA_KEY, com.kista.shared.kernel.OrderDirection.BUY, LocalDate.of(2026, 9, 30),
                com.kista.shared.kernel.DcaSkipReason.CONDITION_NOT_MET, "상세", null);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(dca);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());
        DcaStrategyConfig keyed = TradingFixtures.dcaConfig(TradingFixtures.stockDcaRule(DCA_KEY, OrderDirection.BUY, "300", OrderType.MOC));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(keyed));
        when(dcaExecutionPort.findLatestByStrategyIds(List.of(STRATEGY_ID))).thenReturn(Map.of(STRATEGY_ID, Map.of(DCA_KEY, last)));
        stubUsCalendar();

        StrategyDetail detail = strategyService.getById(STRATEGY_ID, USER_ID);

        assertThat(detail.dca().rules()).singleElement().satisfies(r -> {
            assertThat(r.rule()).isEqualTo(keyed.rules().getFirst());
            assertThat(r.lastExecution()).isEqualTo(last);
            assertThat(r.nextKstTradeDate()).isNotNull();
        });
        assertThat(detail.currentRound()).isNull();
    }

    @Test
    @DisplayName("코인 DCA 전략 조회는 소수 보유 원장이어도 예외 없이 currentHoldings를 null로 둔다")
    void getById_cryptoDca_fractionalHoldings_noException() {
        Strategy dca = dcaStrategy(STRATEGY_ID, StrategyTicker.BTC);
        CyclePosition fractional = new CyclePosition(null, CYCLE.id(), BigDecimal.ZERO, null, null,
                new BigDecimal("0.00123"), null, null);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(dca);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID)); // 시장은 계좌 브로커에서
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(fractional));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(
                TradingFixtures.dcaConfig(TradingFixtures.cryptoDcaRule(DCA_KEY, OrderDirection.BUY, "10000", 9))));
        when(dcaExecutionPort.findLatestByStrategyIds(List.of(STRATEGY_ID))).thenReturn(Map.of());

        StrategyDetail detail = strategyService.getById(STRATEGY_ID, USER_ID);

        assertThat(detail.currentHoldings()).isNull();
        assertThat(detail.dca().currentHoldings()).isEqualByComparingTo("0.00123"); // 원장 소수 보유는 DCA 블록이 싣는다
    }

    @Test
    @DisplayName("DCA 등록도 사용자 프로필(복제본)이 없으면 거부한다")
    void register_dca_rejectsWhenProfileMissing() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of());
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.BUY))))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(strategyPort, never()).save(any());
    }
}
