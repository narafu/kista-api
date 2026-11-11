package com.kista.tradingcore.trading.application.service;
import static com.kista.shared.support.FixedClockTestConfig.FIXED;

import com.kista.tradingcore.trading.application.service.support.UnassignedCashCalculator;
import com.kista.tradingcore.trading.domain.creation.VrRampInput;
import com.kista.tradingcore.trading.domain.creation.VrRampParams;
import com.kista.tradingcore.trading.application.service.support.StrategyHistoryQueryService;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.trading.domain.model.*;
import com.kista.tradingcore.matching.domain.model.*;
import com.kista.shared.kernel.RecurringMode;
import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyFieldSettings;
import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import com.kista.tradingcore.trading.application.port.output.StrategyCreationPolicyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort; import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
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
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.kista.tradingcore.support.StrategyCommands.*;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.kista.shared.kernel.StrategyDefaults;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.tradingcore.privacy.application.port.output.PrivacyTradePort;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.NoSuchElementException;

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
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock OrderPort orderPort;                                   // 전략별 주문 내역 조회
    @Mock PrivacyTradePort privacyTradePort; // 시드 미리보기 PRIVACY 기준표
    @Mock StrategyDcaDetailPort dcaDetailPort;                   // DCA 버전 설정 포트
    @Mock DcaExecutionPort dcaExecutionPort;                     // DCA 회차 이력 포트
    @Mock MarketCalendarPort marketCalendarPort;                 // DCA 다음 실행일 계산용 캘린더
    @Mock CycleCapitalEventPort cycleCapitalEventPort;           // 투자금 추가·인출 이력 존재 확인
    @Mock TradingBatchRunPort batchRunPort;                      // 보관 전 당일 마감 반영 여부
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

    // ARCHIVED 상태 전략 픽스처
    private static final Strategy ARCHIVED_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ARCHIVED,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE
    );

    private static final UUID CYCLE_ID = UUID.randomUUID();

    // 현재 사이클 픽스처 (시작금액 1000)
    private static final StrategyCycle CYCLE = new StrategyCycle(
            CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"), null, LocalDate.now(FIXED), null, null, null
    );

    @BeforeEach
    void setUp() {
        lenient().when(strategyPort.archiveIfPaused(any())).thenReturn(true); // 보관 확정 기본 성공
        lenient().when(strategyPort.updateCycleSeedTypeUnlessArchived(any(), any())).thenReturn(true); // 정책 조건부 갱신 기본 성공
        lenient().when(strategyPort.updateStatusUnlessArchived(any(), any())).thenReturn(true); // 조건부 상태 갱신 기본 성공(보관 경합 테스트에서 재스텁)
        DcaSummaryAssembler dcaSummaryAssembler = new DcaSummaryAssembler(marketCalendarPort);
        StrategyDetailAssembler detailAssembler = new StrategyDetailAssembler(
                strategyVersionPort, strategyInfiniteDetailPort, vrStrategyDetailService, strategyCyclePort,
                cyclePositionPort, cyclePositionInfiniteDetailPort, dcaDetailPort, dcaExecutionPort, dcaSummaryAssembler, FIXED);
        InfiniteMinSeedGuard minSeedGuard = new InfiniteMinSeedGuard(brokerPricePort,
                new CycleOrderStrategies(List.of(
                        new InfiniteCycleOrderStrategy(null, null))),
                strategyInfiniteDetailPort);
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
                minSeedGuard,
                detailAssembler, FIXED);
        var historyStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(null, null),
                new PrivacyCycleOrderStrategy(null)));
        StrategyHistoryQueryService historyQueryService = new StrategyHistoryQueryService(
                accountPort, strategyPort, cyclePositionPort, orderPort, historyStrategies,
                privacyTradePort, brokerPricePort,
                new UnassignedCashCalculator(strategyPort, cyclePositionPort, marginPort, null), FIXED);
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
                        strategyCyclePort, cyclePositionPort, strategyCycleVrPort, strategyVersionPort, vrStrategyDetailService, cycleCapitalEventPort, FIXED),
                orderCancelService,
                eventPublisher,
                new StrategyStateWriter(strategyPort, strategyCyclePort, cyclePositionPort),
                orderPort, batchRunPort, minSeedGuard,
                new StrategyUpdateWriter(strategyPort, accountPort, strategyCyclePort, cyclePositionPort, cycleCapitalEventPort,
                        detailAssembler, eventPublisher, FIXED, batchRunPort, orderPort, marketCalendarPort),
                FIXED);
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

    private RegisterStrategyCommand commandFor(StrategyType type, StrategyTicker ticker, Integer divisionCount,
                                                Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount) {
        // 중간부터 시작 입력(initialHoldings/initialAvgPrice) 없음 — holdings=0에서 시작하는 기존 시나리오 전용 헬퍼
        return register(type).ticker(ticker).divisionCount(divisionCount).intervalWeeks(intervalWeeks).bandWidth(bandWidth).recurringAmount(recurringAmount).build();
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

        verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED);
    }

    @Test
    @DisplayName("pauseWithPlacedCryptoOrderIsRefused: 코인 체결 확인 중이면 PAUSED 기록 전에 거부")
    void pauseWithPlacedCryptoOrderIsRefused() {
        Account account = ownerAccount();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        doThrow(new OrderCancelException("체결 확인 중인 코인 주문이 있어 잠시 후 다시 시도해 주세요."))
                .when(orderCancelService).requireNoCryptoInFlight(STRATEGY_ID, account);

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, USER_ID))
                .isInstanceOf(OrderCancelException.class);

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
        inOrder.verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED);
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
        inOrder.verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED);
        inOrder.verify(orderCancelService).cancelOpenOrders(STRATEGY_ID, account);
        inOrder.verify(cyclePositionPort).deleteByStrategyId(STRATEGY_ID);
        inOrder.verify(strategyCyclePort).deleteByStrategyId(STRATEGY_ID);
        inOrder.verify(strategyPort).delete(STRATEGY_ID);
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(USER_ID)); // stats 캐시 무효화
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

        verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED);
        verify(strategyPort, never()).delete(any());
        verify(strategyCyclePort, never()).deleteByStrategyId(any());
        verify(cyclePositionPort, never()).deleteByStrategyId(any());
    }

    @Test
    void pause_keeps_archived_status() {
        when(strategyPort.updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED)).thenReturn(false);

        assertThat(new StrategyStateWriter(strategyPort, strategyCyclePort, cyclePositionPort).pause(STRATEGY_ID)).isFalse();

        verify(strategyPort, never()).save(any());
    }

    private static final String ARCHIVED_MESSAGE = "보관된 전략은 변경할 수 없습니다.";

    @Test
    void pause_rejects_archived_strategy() {
        stubArchivable(ARCHIVED_STRATEGY);

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage(ARCHIVED_MESSAGE);

        verify(orderCancelService, never()).cancelOpenOrders(any(), any());
        verify(strategyPort, never()).save(any());
    }

    @Test
    void resume_rejects_archived_strategy() {
        stubArchivable(ARCHIVED_STRATEGY);

        assertThatThrownBy(() -> strategyService.resume(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage(ARCHIVED_MESSAGE);

        verify(strategyPort, never()).save(any());
    }

    @Test
    void update_rejects_archived_strategy() {
        stubArchivable(ARCHIVED_STRATEGY);

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(null, new BigDecimal("3000"))))
                .isInstanceOf(IllegalStateException.class).hasMessage(ARCHIVED_MESSAGE);

        verify(strategyPort, never()).save(any());
    }

    // --- 목록 보관 전략 제외 ---

    private void stubListing(Strategy active, Strategy archived) {
        Account account = ownerAccount();
        lenient().when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(account));
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        lenient().when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(active, archived)));
        lenient().when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(active, archived));
        lenient().when(strategyCyclePort.findLatestByStrategyIds(any())).thenReturn(Map.of());
    }

    private static Strategy archivedWithNewId() {
        return new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ARCHIVED,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
    }

    @Test
    void listByUserId_excludes_archived_by_default() {
        stubListing(ACTIVE_STRATEGY, archivedWithNewId());

        assertThat(strategyService.listByUserId(USER_ID, false)).extracting(d -> d.strategy().id())
                .containsExactly(STRATEGY_ID);
    }

    @Test
    void listByUserId_includes_archived_when_requested() {
        stubListing(ACTIVE_STRATEGY, archivedWithNewId());

        assertThat(strategyService.listByUserId(USER_ID, true)).hasSize(2);
    }

    @Test
    void listByAccountId_excludes_archived() {
        stubListing(ACTIVE_STRATEGY, archivedWithNewId());

        assertThat(strategyService.listByAccountId(ACCOUNT_ID, USER_ID)).extracting(d -> d.strategy().id())
                .containsExactly(STRATEGY_ID);
    }

    private void stubArchivable(Strategy strategy) {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
    }

    private static CyclePosition positionWithHoldings(int holdings) {
        return new CyclePosition(UUID.randomUUID(), CYCLE_ID, new BigDecimal("500"), new BigDecimal("110"),
                new BigDecimal("100"), BigDecimal.valueOf(holdings), null, null);
    }

    private static Order todayOrder(String externalOrderId, String leg) {
        return new Order(UUID.randomUUID(), ACCOUNT_ID, CYCLE_ID, LocalDate.now(FIXED), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, leg, BigDecimal.ONE, BigDecimal.TEN,
                OrderStatus.PLACED, externalOrderId, null, null);
    }

    @Test
    void archive_saves_strategy_as_archived() {
        stubArchivable(ACTIVE_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(positionWithHoldings(0)));

        strategyService.archive(STRATEGY_ID, USER_ID);

        // pause 기록 → 미체결 정리 → archive 기록 순서
        var inOrder = inOrder(strategyPort, orderCancelService);
        inOrder.verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED);
        inOrder.verify(orderCancelService).cancelOpenOrders(eq(STRATEGY_ID), any());
        inOrder.verify(strategyPort).archiveIfPaused(STRATEGY_ID);
    }

    @Test
    void archive_rejects_when_status_changed_concurrently() {
        // 최종 기록 직전 재개 등으로 PAUSED가 아니면 조건부 갱신 0건 — ARCHIVED로 덮어쓰지 않고 거부
        stubArchivable(ACTIVE_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(positionWithHoldings(0)));
        when(strategyPort.archiveIfPaused(STRATEGY_ID)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.archive(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage("전략 상태가 바뀌어 보관하지 못했습니다. 다시 시도하시기 바랍니다.");
    }

    @Test
    void archive_rejects_when_holdings_nonzero() {
        stubArchivable(ACTIVE_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(positionWithHoldings(3)));

        assertThatThrownBy(() -> strategyService.archive(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("보유 수량을 모두 매도한 뒤 보관할 수 있습니다.");

        verify(orderCancelService, never()).cancelOpenOrders(any(), any());
    }

    @Test
    void archive_allows_when_no_position() {
        stubArchivable(ACTIVE_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());

        strategyService.archive(STRATEGY_ID, USER_ID);

        verify(strategyPort).archiveIfPaused(STRATEGY_ID);
    }

    @Test
    void archive_rejects_when_today_orders_not_reported() {
        stubArchivable(PAUSED_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());
        when(orderPort.findByStrategyId(STRATEGY_ID, LocalDate.now(FIXED), LocalDate.now(FIXED)))
                .thenReturn(List.of(todayOrder("ODNO1", "LOC_BUY")));
        when(batchRunPort.isReported(LocalDate.now(FIXED), STRATEGY_ID)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.archive(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("오늘 접수된 주문이 마감 반영된 뒤 보관할 수 있습니다.");

        verify(strategyPort, never()).save(any());
    }

    @Test
    void archive_rejects_when_next_trade_date_orders_not_reported() {
        LocalDate nextDate = DstInfo.nextTradeDate(ZonedDateTime.now(FIXED));
        stubArchivable(PAUSED_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());
        lenient().when(orderPort.findByStrategyId(STRATEGY_ID, LocalDate.now(FIXED), LocalDate.now(FIXED))).thenReturn(List.of()); // 오늘 날짜 주문 없음
        when(orderPort.findByStrategyId(STRATEGY_ID, nextDate, nextDate)).thenReturn(List.of(todayOrder("ODNO1", "LOC_BUY")));
        when(batchRunPort.isReported(nextDate, STRATEGY_ID)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.archive(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("오늘 접수된 주문이 마감 반영된 뒤 보관할 수 있습니다.");

        verify(strategyPort, never()).save(any());
    }

    @Test
    void archive_recheck_after_cancel_catches_order_placed_in_between() {
        LocalDate nextDate = DstInfo.nextTradeDate(ZonedDateTime.now(FIXED));
        stubArchivable(PAUSED_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());
        // 첫 점검(보관 전)엔 없고 취소 뒤 재점검에서 접수 주문이 보인다
        lenient().when(orderPort.findByStrategyId(STRATEGY_ID, LocalDate.now(FIXED), LocalDate.now(FIXED))).thenReturn(List.of()); // 오늘 날짜 주문 없음
        when(orderPort.findByStrategyId(STRATEGY_ID, nextDate, nextDate))
                .thenReturn(List.of()).thenReturn(List.of(todayOrder("ODNO1", "LOC_BUY")));
        when(batchRunPort.isReported(nextDate, STRATEGY_ID)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.archive(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("오늘 접수된 주문이 마감 반영된 뒤 보관할 수 있습니다.");

        verify(strategyPort, never()).archiveIfPaused(any());
    }

    @Test
    void archive_skips_report_check_for_crypto_account() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());
        lenient().when(orderPort.findByStrategyId(any(), any(), any())).thenReturn(List.of(todayOrder("UUID1", "DCA")));
        lenient().when(batchRunPort.isReported(any(), any())).thenReturn(false); // 코인은 리포트 마커가 없다

        strategyService.archive(STRATEGY_ID, USER_ID);

        verify(strategyPort).archiveIfPaused(STRATEGY_ID);
    }

    @Test
    void archive_allows_when_today_orders_already_reported() {
        stubArchivable(PAUSED_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());
        when(orderPort.findByStrategyId(STRATEGY_ID, LocalDate.now(FIXED), LocalDate.now(FIXED)))
                .thenReturn(List.of(todayOrder("ODNO1", "LOC_BUY")));
        when(batchRunPort.isReported(LocalDate.now(FIXED), STRATEGY_ID)).thenReturn(true);

        strategyService.archive(STRATEGY_ID, USER_ID);

        verify(strategyPort).archiveIfPaused(STRATEGY_ID);
    }

    @Test
    void archive_rejects_already_archived() {
        stubArchivable(ARCHIVED_STRATEGY);

        assertThatThrownBy(() -> strategyService.archive(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 보관된 전략입니다.");
    }

    @Test
    void archive_aborts_when_cancel_fails() {
        Account account = ownerAccount();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());
        when(orderCancelService.cancelOpenOrders(STRATEGY_ID, account)).thenReturn(new CancelResult(0, 1));

        assertThatThrownBy(() -> strategyService.archive(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("증권사 주문 취소에 실패한 주문이 있어 보관할 수 없습니다. 잠시 후 다시 시도하시기 바랍니다.");

        verify(strategyPort, never()).archiveIfPaused(any());
    }

    @Test
    void archive_from_paused_succeeds() {
        stubArchivable(PAUSED_STRATEGY);
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());

        strategyService.archive(STRATEGY_ID, USER_ID);

        verify(strategyPort).archiveIfPaused(STRATEGY_ID);
    }

    @Test
    void delete_archived_strategy_soft_deletes() {
        stubArchivable(ARCHIVED_STRATEGY);
        when(strategyPort.updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED)).thenReturn(false); // 보관 행은 갱신 0건 — 삭제는 그래도 진행

        strategyService.delete(STRATEGY_ID, USER_ID);

        // ARCHIVED는 PAUSED로 되돌려 저장하지 않고 취소·소프트 삭제만 진행한다
        verify(strategyPort, never()).save(any());
        verify(orderCancelService).cancelOpenOrders(eq(STRATEGY_ID), any());
        verify(strategyPort).delete(STRATEGY_ID);
    }

    @Test
    @DisplayName("resume() 호출 시 전략 상태가 ACTIVE로 저장된다")
    void resume_saves_strategy_as_active() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        strategyService.resume(STRATEGY_ID, USER_ID);

        verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.ACTIVE);
    }

    @Test
    void resume_rejects_when_archived_concurrently() {
        // 읽은 뒤 보관이 커밋된 경합 — 조건부 갱신이 0건이면 거부(ARCHIVED 덮어쓰기 방지)
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.resume(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage(ARCHIVED_MESSAGE);
    }

    @Test
    void pause_rejects_when_archived_concurrently() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.PAUSED)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage(ARCHIVED_MESSAGE);

        verify(orderCancelService, never()).cancelOpenOrders(any(), any());
    }

    @Test
    @DisplayName("resume() 호출 시 최신 사이클이 종료됐으면(endDate 존재) endAmount를 시드로 새 사이클을 재오픈한다")
    void resume_reopens_ended_cycle_with_end_amount_as_seed() {
        StrategyCycle endedCycle = new StrategyCycle(
                CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"), new BigDecimal("874.50"),
                LocalDate.now(FIXED).minusDays(5), LocalDate.now(FIXED), null, null);
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
                p.deposit().compareTo(new BigDecimal("874.50")) == 0 && p.holdings().signum() == 0));
        verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.ACTIVE);
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(USER_ID)); // 새 사이클 생성 → stats 캐시 무효화
    }

    @ParameterizedTest
    @CsvSource({
            "MAINTAIN, 1200, 1000",   // 수익 후 — 시작 시드로 복귀
            "MAINTAIN, 874.50, 874.50", // 손실 후 — 남은 원장
            "MAX, 1200, 1200",
            "NONE, 1200, 1200"
    })
    @DisplayName("resume() — 종료 사이클 재오픈 시드는 cycleSeedType 정책을 따른다(MAINTAIN=min(시작 시드, 종료 원장))")
    void resume_reopen_seed_follows_cycle_seed_type(StrategyCycleSeedType seedType, String endAmount, String expectedSeed) {
        stubEndedCycleResume(endAmount, "60.00");
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY.withCycleSeedType(seedType));
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenAnswer(i -> i.getArgument(0));

        strategyService.resume(STRATEGY_ID, USER_ID);

        ArgumentCaptor<StrategyCycle> cycleCaptor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(cycleCaptor.capture());
        assertThat(cycleCaptor.getValue().startAmount()).isEqualByComparingTo(expectedSeed);
    }

    @Test
    @DisplayName("resume() — MAINTAIN은 1주 미만 판정도 재오픈 시드(min) 기준이다")
    void resume_maintain_rejects_when_reopen_seed_below_one_share() {
        stubEndedCycleResume("1200", "1100.00"); // 시작 시드 1000 < 1주 1100 ≤ 종료 원장 1200
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY.withCycleSeedType(StrategyCycleSeedType.MAINTAIN));

        assertThatThrownBy(() -> strategyService.resume(STRATEGY_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("남은 원장($1000.00)이 1주 가격($1100.00)보다 작아");
        verify(strategyCyclePort, never()).save(any());
        verify(strategyPort, never()).save(any());
    }

    private void stubEndedCycleResume(String endAmount, String closingPrice) {
        StrategyCycle endedCycle = new StrategyCycle(
                CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"), new BigDecimal(endAmount),
                LocalDate.now(FIXED).minusDays(5), LocalDate.now(FIXED), null, null);
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
                .hasMessage("남은 원장($12.34)이 1주 가격($60.00)보다 작아 재개할 수 없습니다. 전략을 삭제한 뒤 충분한 투자금으로 다시 등록해야 합니다.");
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
        verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.ACTIVE);
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
        verify(strategyPort).updateStatusUnlessArchived(STRATEGY_ID, StrategyStatus.ACTIVE);
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
        when(strategyPort.updateCycleSeedTypeUnlessArchived(STRATEGY_ID, StrategyCycleSeedType.MAINTAIN)).thenReturn(true);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));

        StrategyDetail result = strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(StrategyCycleSeedType.MAINTAIN, null));

        assertThat(result.strategy().cycleSeedType()).isEqualTo(StrategyCycleSeedType.MAINTAIN);
        assertThat(result.initialDeposit()).isEqualTo(new BigDecimal("1000.00"));
        // 전체 save가 아니라 정책 컬럼만 조건부 갱신한다(오래된 스냅샷이 status를 덮지 않게)
        verify(strategyPort, never()).save(any());
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateBalance(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("update() 사전 검사 뒤 동시에 보관되면(조건부 갱신 0건) 변경 불가 예외를 던진다")
    void update_concurrentlyArchived_throws() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.updateCycleSeedTypeUnlessArchived(STRATEGY_ID, StrategyCycleSeedType.MAINTAIN)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(StrategyCycleSeedType.MAINTAIN, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("update() holdings가 있으면 시드 수정을 거절한다")
    void update_seed_with_holdings_throws() {
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), new BigDecimal("110"), new BigDecimal("100"), BigDecimal.valueOf(10), null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(CYCLE);
        when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(null, new BigDecimal("5000"))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateBalance(any(), any());
    }

    // 시드 수정 공통 스텁 — holdings 0·예수금 1000 최신 포지션
    private CyclePosition stubSeedUpdate(StrategyCycle cycle) {
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000"), new BigDecimal("110"), null, BigDecimal.ZERO, null, null);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        lenient().when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(cycle);
        lenient().when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));
        return latest;
    }

    @Test
    @DisplayName("update() 시드 수정은 openAmount를 건드리지 않고 최신 행 예수금을 바꾸며 차액을 RESET 이력으로 남긴다")
    void update_seed_change_recordsResetEvent() {
        CyclePosition latest = stubSeedUpdate(CYCLE);

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("3000")));

        verify(strategyCyclePort).lockForUpdate(CYCLE_ID);
        verify(strategyCyclePort).updateStartAmount(CYCLE_ID, new BigDecimal("3000"));
        verify(cyclePositionPort).updateBalance(latest.id(), latest.toBalance().withDeposit(new BigDecimal("3000")));
        ArgumentCaptor<CycleCapitalEvent> event = ArgumentCaptor.forClass(CycleCapitalEvent.class);
        verify(cycleCapitalEventPort).save(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(CycleCapitalEvent.Kind.RESET);
        assertThat(event.getValue().cyclePositionId()).isEqualTo(latest.id());
        assertThat(event.getValue().strategyCycleId()).isEqualTo(CYCLE_ID);
        assertThat(event.getValue().depositDelta()).isEqualByComparingTo("2000");
        assertThat(event.getValue().holdingsDelta()).isEqualByComparingTo("0");
        assertThat(event.getValue().principalDelta()).isEqualByComparingTo("2000");
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(USER_ID));
    }

    @Test
    @DisplayName("update() 투자금 추가·인출 이력이 있어도 시드 수정이 허용된다(RESET 이력으로 원금 유지)")
    void update_seed_allowedWithPriorCapitalHistory() {
        stubSeedUpdate(CYCLE);

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("500")));

        verify(strategyCyclePort).updateStartAmount(CYCLE_ID, new BigDecimal("500"));
        ArgumentCaptor<CycleCapitalEvent> event = ArgumentCaptor.forClass(CycleCapitalEvent.class);
        verify(cycleCapitalEventPort).save(event.capture());
        assertThat(event.getValue().principalDelta()).isEqualByComparingTo("-500");
    }

    @Test
    @DisplayName("update() 종료된 사이클의 시드 수정은 거절하고 아무것도 쓰지 않는다")
    void update_seed_endedCycle_rejected() {
        StrategyCycle ended = new StrategyCycle(CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"),
                new BigDecimal("1100"), LocalDate.now(FIXED), LocalDate.now(FIXED), null, null);
        stubSeedUpdate(ended);

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(null, new BigDecimal("3000"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("종료된 사이클은 예수금을 수정할 수 없습니다. 재개한 뒤 수정해야 합니다.");

        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateBalance(any(), any());
        verify(cycleCapitalEventPort, never()).save(any());
    }

    @Test
    @DisplayName("update() 마감 리포트 창에는 시드 수정을 거절한다")
    void update_seed_reportWindow_rejected() {
        stubSeedUpdate(CYCLE);
        LocalDate today = LocalDate.now(FIXED);
        when(orderPort.findByStrategyId(STRATEGY_ID, today, today)).thenReturn(List.of(todayOrder("ODNO1", "LOC_BUY")));
        when(batchRunPort.isReported(today, STRATEGY_ID)).thenReturn(false);

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(null, new BigDecimal("3000"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("오늘 마감 리포트가 끝나기 전에는 예수금을 수정할 수 없습니다.");

        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cycleCapitalEventPort, never()).save(any());
    }

    @Test
    @DisplayName("update() 시드가 현재 예수금과 같으면 이력을 남기지 않는다")
    void update_seed_same_noEvent() {
        stubSeedUpdate(CYCLE);

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("1000")));

        verify(cycleCapitalEventPort, never()).save(any());
    }

    @Test
    @DisplayName("update() 시드가 0 이하이면 IllegalArgumentException 발생")
    void update_seed_zero_or_negative_throws() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

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
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(opening));

        assertThatThrownBy(() -> strategyService.update(
                STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("3000.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VR 재설정");

        verify(strategyPort, never()).save(any());
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateBalance(any(), any());
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
    @DisplayName("update() DCA의 연속 사이클 정책을 NONE 외 값으로 바꾸면 저장 전에 거절한다")
    void update_dcaCycleSeedType_nonNone_throws() {
        Strategy dcaStrategy = new Strategy(
                STRATEGY_ID, ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(dcaStrategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.update(
                STRATEGY_ID, USER_ID, new UpdateStrategyCommand(StrategyCycleSeedType.MAX, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("연속 사이클 정책");

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("update() DCA에 newSeed를 주면 일반 수정으로 예수금을 바꿀 수 없다고 거절한다")
    void update_dcaNewSeed_throws() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(new Strategy(
                STRATEGY_ID, ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("3000"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DCA 전략의 예수금은 일반 수정으로 변경할 수 없습니다. 투자금 추가·인출을 이용해야 합니다.");
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
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

        List<StrategyDetail> result = strategyService.listByUserId(USER_ID, false);

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
                commandFor(StrategyType.INFINITE, null, null, null, null, null));

        verify(accountPort, times(1)).requireOwnedAccount(ACCOUNT_ID, USER_ID);
        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(USER_ID)); // stats 캐시 무효화
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
                commandFor(StrategyType.INFINITE, null, null, null, null, null));

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
                vr().deposit("1000").build());

        assertThat(result.strategy().ticker()).isEqualTo(StrategyTicker.TQQQ);
        verify(strategyVrDetailPort).save(argThat(detail -> detail.intervalWeeks() == 2
                && detail.bandWidth().compareTo(new BigDecimal("15")) == 0
                && detail.recurringAmount() == 0));
    }

    @Test
    @DisplayName("INFINITE register()는 생략한 divisionCount에 정책 기본값을 저장한다")
    void register_infinite_omittedDivisionCount_storesPolicyDefault() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);

        strategyService.register(USER_ID, ACCOUNT_ID, infinite().deposit("1000").build());

        verify(strategyInfiniteDetailPort).save(argThat(detail -> detail.divisionCount() == StrategyDefaults.DEFAULT_DIVISION_COUNT));
    }

    @Test
    @DisplayName("INFINITE register()는 divisionCount=0 명시 입력을 허용 목록 밖으로 거부한다")
    void register_infinite_explicitZeroDivisionCount_rejected() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                infinite().deposit("1000").divisionCount(0).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않는 값");
    }

    @Test
    @DisplayName("register()는 활성 종목 유니크 인덱스 위반을 DuplicateStrategyTickerException으로 변환한다")
    void register_activeTickerIndexViolation_translatesToDuplicate() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any())).thenThrow(new DataIntegrityViolationException("저장 실패",
                new RuntimeException("duplicate key value violates unique constraint \"uq_strategy_account_ticker_active\"")));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, infinite().build()))
                .isInstanceOf(DuplicateStrategyTickerException.class);
    }

    @Test
    @DisplayName("register()는 다른 제약 위반은 변환하지 않고 그대로 던진다")
    void register_otherConstraintViolation_isNotTranslated() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any())).thenThrow(new DataIntegrityViolationException("저장 실패",
                new RuntimeException("duplicate key value violates unique constraint \"other_key\"")));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, infinite().build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("INFINITE register()는 initialDeposit null이면 0으로 저장한다")
    void register_infinite_nullDeposit_persistsZero() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);

        strategyService.register(USER_ID, ACCOUNT_ID, infinite().build());

        verify(cyclePositionPort).save(argThat(p -> p.deposit().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("PRIVACY register()는 initialDeposit null이면 0으로 저장한다")
    void register_privacy_nullDeposit_persistsZero() {
        stubSuccessfulRegistration(StrategyType.PRIVACY, StrategyTicker.SOXL);

        strategyService.register(USER_ID, ACCOUNT_ID, register(StrategyType.PRIVACY).build());

        verify(cyclePositionPort).save(argThat(p -> p.deposit().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register()는 recurringAmount 생략 시 기본 방향이 입금이면 입력을 요구한다")
    void register_vr_omittedRecurringAmount_depositDefaultMode_rejected() {
        StrategyCreationSettings hold = defaultTradingSettings(StrategyType.VR);
        when(strategyCreationPolicyPort.find(StrategyType.VR)).thenReturn(Optional.of(new StrategyCreationSettings(
                true, hold.ticker(), null,
                new StrategyFieldSettings<>(true, List.of(RecurringMode.values()), RecurringMode.DEPOSIT),
                hold.bandWidth(), hold.intervalWeeks())));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                vr().deposit("1000").intervalWeeks(2).bandWidth("15.00").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정기 입출금액을 입력해야 합니다.");
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
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, null));

        verifyNoInteractions(strategyCreationPolicyPort);
    }

    @Test
    @DisplayName("register() 호출 시 같은 계좌에 동일 종목 전략이 이미 있으면 IllegalStateException 발생")
    void register_duplicateTicker_throws() {
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).divisionCount(20).build();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.SOXL)).thenReturn(List.of(ACTIVE_STRATEGY));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalStateException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register() 호출 시 시드가 가용 예수금을 넘어도 등록은 성공하고 증권사 잔고를 조회하지 않는다")
    void registerOverFreeCashIsAllowed() {
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.TQQQ).deposit("600").divisionCount(20).build();
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.TQQQ);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.initialDeposit()).isEqualByComparingTo("600");
        verifyNoInteractions(marginPort);
    }

    // --- VR register() ---

    @Test
    @DisplayName("VR register() holdings=0이어도 초기 V 입력이 있으면 그 값을 V값으로 저장한다")
    void register_vr_explicitInitialValue_holdingsZero_usesOverrideAsValue() {
        RegisterStrategyCommand cmd = vr().deposit(BigDecimal.ZERO).intervalWeeks(2).bandWidth("15.00").recurringAmount(0).vrValue("5000").build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(FIXED), null, null, null);
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
        RegisterStrategyCommand cmd = vr().deposit(BigDecimal.ZERO).holdings(BigDecimal.valueOf(10)).avgPrice(new BigDecimal("100.00")).intervalWeeks(2).bandWidth("15.00").recurringAmount(0).vrValue("99999").build();
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
        RegisterStrategyCommand cmd = vr().deposit("100").intervalWeeks(2).bandWidth("15.00").recurringAmount(-100).vrValue("100000").build();
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
        RegisterStrategyCommand cmd = vr().intervalWeeks(2).bandWidth("15.00").recurringAmount(0).vrValue("-1").build();
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값은 0 이상이어야 합니다");
    }

    @Test
    @DisplayName("VR register() 성공 — StrategyVrDetail·StrategyCycleVrDetail 저장, poolLimit 계산, cycleSeedType NONE 강제")
    void register_vr_success_savesVrDetailsAndPoolLimit() {
        // holdings=0(중간부터 시작 미입력) → V=0, 초기 자산 2000(예수금만), poolLimitRate=0.75(recurringAmount=0) → poolLimit = 1500.00
        RegisterStrategyCommand cmd = vr().deposit("2000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000"), null, LocalDate.now(FIXED), null, null, null);
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
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(2).bandWidth("10.00").recurringAmount(0).build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1000"), null, LocalDate.now(FIXED), null, null, null);
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
        RegisterStrategyCommand cmd = vr().deposit(BigDecimal.ZERO).intervalWeeks(2).bandWidth("15.00").recurringAmount(200).build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(FIXED), null, null, null);
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
        RegisterStrategyCommand hold = vr().deposit(BigDecimal.ZERO).intervalWeeks(2).bandWidth("15.00").recurringAmount(0).build();
        RegisterStrategyCommand withdraw = vr().deposit(BigDecimal.ZERO).intervalWeeks(2).bandWidth("15.00").recurringAmount(-100).build();
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
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(2).bandWidth("15.00").recurringAmount(-100).build();
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
        RegisterStrategyCommand cmd = vr().deposit("2000").intervalWeeks(4).bandWidth("15.00").build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000"), null, LocalDate.now(FIXED), null, null, null);
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
        RegisterStrategyCommand cmd = vr().deposit(BigDecimal.ZERO).intervalWeeks(4).bandWidth("15.00").recurringAmount(200).build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(FIXED), null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenAnswer(inv -> inv.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() 적립식 initialDeposit null이면 0으로 정규화한다")
    void register_vr_recurringDeposit_nullPool_defaultsToZero() {
        RegisterStrategyCommand cmd = vr().intervalWeeks(4).bandWidth("15.00").recurringAmount(200).build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(FIXED), null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenAnswer(inv -> inv.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // recurringAmount=200(적립식) → 기본 poolLimitRate=1.0 (개장 pool=0이라 달러 파생 poolLimit은 0이지만 저장 비율 자체는 1.0)
        verify(strategyCycleVrPort).save(argThat(cv -> cv.poolLimitRate().compareTo(BigDecimal.ONE) == 0));
        verify(cyclePositionPort).save(argThat(p -> p.deposit().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() poolLimit scale=2 HALF_UP — recurringAmount > 0 시 poolLimitRate=1.0")
    void register_vr_poolLimitRate_withDeposit() {
        // holdings=0 → V=0, recurringAmount=100(입금) → poolLimitRate=1.0 → poolLimit = 1000 × 1.0 = 1000.00
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(100).build();
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1000"), null, LocalDate.now(FIXED), null, null, null);
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
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).build();

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
        // 인출식: recurringAmount=-100, intervalWeeks=2 → 필요 최소자산 = 100×100×4/2 = 20000, initialDeposit=20000으로 충족
        RegisterStrategyCommand cmd = vr().deposit("20000").intervalWeeks(2).bandWidth("15.00").recurringAmount(-100).build();

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
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).vrRamp(new VrRampInput(12, 10, 5, 18, new BigDecimal("0.60"), 8, 4, new BigDecimal("0.30"))).build();

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
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).vrRamp(new VrRampInput(null, null, null, null, new BigDecimal("0.60"), 0, 0, BigDecimal.ZERO)).build();

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.pStepWeeks() == 0 && d.pGraceWeeks() == 0
                        && d.poolLimitFloor().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() gStepWeeks=0 — gradient 램프 비활성화, gGraceWeeks=0·gMax=0을 그대로 저장")
    void register_vr_gStepWeeksZero_disablesRampAndSkipsMaxValidation() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).vrRamp(new VrRampInput(10, 0, 0, 0, null, null, null, null)).build();

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
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).vrRamp(new VrRampInput(null, null, null, null, new BigDecimal("0.60"), 0, 0, new BigDecimal("0.90"))).build();

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool 한도율 하한");

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("VR register() gMax < initialGradient이면 IllegalArgumentException")
    void register_vr_gMaxLessThanInitialGradient_throws() {
        Account account = ownerAccount();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).vrRamp(new VrRampInput(15, null, null, 10, null, null, null, null)).build();

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gradient 상한");

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("VR register() poolLimitFloor > initialPoolLimitRate이면 IllegalArgumentException")
    void register_vr_poolLimitFloorGreaterThanInitial_throws() {
        Account account = ownerAccount();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).vrRamp(new VrRampInput(null, null, null, null, new BigDecimal("0.50"), null, null, new BigDecimal("0.80"))).build();

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool 한도율 하한");

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
                new BigDecimal("1600"), null, LocalDate.now(FIXED), null, null, null);
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

        List<StrategyDetail> result = strategyService.listByUserId(USER_ID, false);

        assertThat(result).hasSize(1);
        StrategyDetail detail = result.getFirst();
        assertThat(detail.divisionCount()).isNull();
        assertThat(detail.currentRound()).isNull();
        // toDetail()이 조립하는 startDate는 findLatestByStrategyId로 조회한 사이클의 startDate와 일치해야 함
        assertThat(detail.startDate()).isEqualTo(vrCycle.startDate());
        assertThat(detail.initialDeposit()).isEqualByComparingTo("1000.00");
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
                null, LocalDate.now(FIXED), null, null, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(vrStrategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(vrStrategyId))).thenReturn(Map.of(vrStrategyId, vrCycle));
        // cyclePositionPort.findFirstByCycleIds는 stub하지 않음 — 빈 Map 기본값으로 개장 포지션 누락을 재현

        assertThatThrownBy(() -> strategyService.listByUserId(USER_ID, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션이 없습니다");
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
                new BigDecimal("1000.00"), null, LocalDate.now(FIXED), null, null, null);
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
        when(cyclePositionInfiniteDetailPort.findLatestByCycleId(cycleId, 1)).thenReturn(List.of(reverseDetail));

        StrategyDetail single = strategyService.getById(strategyId, USER_ID);

        // 배치 경로(listByUserId) stub — 동일 데이터를 배치 메서드로 제공
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, cycle));
        when(cyclePositionPort.findLatestByCycleIds(List.of(cycleId))).thenReturn(Map.of(cycleId, position));
        when(strategyVersionPort.findActiveByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, version));
        when(strategyInfiniteDetailPort.findByStrategyVersionIds(List.of(versionId))).thenReturn(Map.of(versionId, infiniteDetail));
        when(cyclePositionInfiniteDetailPort.findLatestByCycleIds(List.of(cycleId))).thenReturn(Map.of(cycleId, reverseDetail));

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID, false);

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
                new BigDecimal("1600.00"), null, LocalDate.now(FIXED), null, null, null);
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

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID, false);

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
                new BigDecimal("2000.00"), null, LocalDate.now(FIXED), null, null, null);
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

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID, false);

        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst()).isEqualTo(single);
        assertThat(single.divisionCount()).isNull();
        assertThat(single.currentRound()).isNull(); // 회차는 INFINITE 전용 — 보유 이력이 있어도 PRIVACY는 null
        assertThat(single.vr()).isNull();
        assertThat(single.cycleInProgress()).isTrue(); // 최신 사이클 endDate=null
        assertThat(single.ledgerDeposit()).isEqualByComparingTo("1500.00"); // 최신 포지션 장부 예수금
    }

    @Test
    @DisplayName("동등성: 최신 사이클이 종료된 전략 — 단건·목록 모두 cycleInProgress=false (투자금 추가 NO_ACTIVE_CYCLE 기준)")
    void equivalence_endedCycle_cycleNotInProgress() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, ACCOUNT_ID, StrategyType.PRIVACY,
                StrategyStatus.PAUSED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle endedCycle = new StrategyCycle(cycleId, strategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000.00"), new BigDecimal("2100.00"), LocalDate.now(FIXED).minusDays(10),
                LocalDate.now(FIXED), null, null);

        // 단건 경로
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.of(endedCycle));

        StrategyDetail single = strategyService.getById(strategyId, USER_ID);

        // 배치 경로
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, endedCycle));

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID, false);

        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst()).isEqualTo(single);
        assertThat(single.cycleInProgress()).isFalse();
        assertThat(single.ledgerDeposit()).isNull(); // 종료 사이클 + 포지션 stub 없음
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

        List<StrategyDetail> batch = strategyService.listByUserId(USER_ID, false);

        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst()).isEqualTo(single);
        assertThat(single.startDate()).isNull();
        assertThat(single.initialDeposit()).isNull();
        assertThat(single.divisionCount()).isEqualTo(StrategyDefaults.DEFAULT_DIVISION_COUNT);
        assertThat(single.currentRound()).isNull();
        assertThat(single.currentHoldings()).isNull();
        assertThat(single.cycleInProgress()).isFalse(); // 사이클 없음
        assertThat(single.ledgerDeposit()).isNull(); // 포지션 없음
    }

    @Test
    @DisplayName("동등성: VR 개장 포지션 없음 — getById와 listByUserId 모두 동일한 IllegalStateException을 던진다")
    void equivalence_vrMissingOpeningPosition_bothThrowSameException() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy strategy = new Strategy(strategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, UUID.randomUUID(),
                new BigDecimal("1600.00"), null, LocalDate.now(FIXED), null, null, null);

        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyCyclePort.findLatestByStrategyId(strategyId)).thenReturn(Optional.of(cycle));
        // cyclePositionPort.findFirstOne은 stub하지 않음 — Optional.empty() 기본값으로 개장 포지션 누락 재현

        assertThatThrownBy(() -> strategyService.getById(strategyId, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션이 없습니다");

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(strategy)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyId))).thenReturn(Map.of(strategyId, cycle));
        // cyclePositionPort.findFirstByCycleIds도 stub하지 않음 — 빈 Map 기본값

        assertThatThrownBy(() -> strategyService.listByUserId(USER_ID, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션이 없습니다");
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
                new BigDecimal("1000.00"), null, LocalDate.now(FIXED), null, null, null);
        StrategyCycle cycleB = new StrategyCycle(cycleBId, strategyBId, STRATEGY_VERSION_ID,
                new BigDecimal("2000.00"), null, LocalDate.now(FIXED), null, null, null);

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID)))
                .thenReturn(Map.of(ACCOUNT_ID, List.of(strategyA, strategyB)));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(strategyAId, strategyBId)))
                .thenReturn(Map.of(strategyAId, cycleA, strategyBId, cycleB));

        strategyService.listByUserId(USER_ID, false);

        verify(strategyPort, never()).findByAccountId(any());
        verify(strategyCyclePort, never()).findLatestByStrategyId(any());
        verify(cyclePositionPort, never()).findLatestOneByStrategyId(any());
        verify(cyclePositionPort, never()).findFirstOne(any());
        verify(strategyVersionPort, never()).findActiveByStrategyId(any());
        verify(strategyInfiniteDetailPort, never()).findActiveByStrategyId(any());
        verify(strategyVrDetailPort, never()).findActiveByStrategyId(any());
        verify(strategyCycleVrPort, never()).findByCycleId(any());
        verify(cyclePositionInfiniteDetailPort, never()).findLatestByCycleId(any(), anyInt());
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
                new BigDecimal("1500.00"), null, LocalDate.now(FIXED), null, null, null);

        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).deposit("1500").divisionCount(20).holdings(BigDecimal.valueOf(10)).avgPrice(new BigDecimal("45.50")).build();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(StrategyTicker.SOXL, account.toBrokerRef())).thenReturn(new BigDecimal("50.00"));
        when(strategyPort.save(any())).thenReturn(saved);
        when(strategyCyclePort.save(any())).thenReturn(savedCycle);
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // startAmount = 현금 1500 + 시장가 50.00×10 = 2000.00 (최소 시드 50×20×2 = 2000 충족)
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("2000.00")) == 0
                && c.openAmount().compareTo(new BigDecimal("2000.00")) == 0)); // 비PRIVACY는 개장 평가 == 시작 금액
        verify(cyclePositionPort).save(argThat(p ->
                p.holdings().compareTo(BigDecimal.valueOf(10)) == 0
                        && p.avgPrice().compareTo(new BigDecimal("45.50")) == 0
                        && p.closingPrice().compareTo(new BigDecimal("50.00")) == 0
                        && p.deposit().compareTo(new BigDecimal("1500")) == 0));
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
                new BigDecimal("2200.00"), null, LocalDate.now(FIXED), null, null, null);

        RegisterStrategyCommand cmd = register(StrategyType.PRIVACY).deposit("200").holdings(BigDecimal.valueOf(20)).avgPrice(new BigDecimal("95.00")).build();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(brokerPricePort.getPrevClose(ticker, account.toBrokerRef())).thenReturn(new BigDecimal("100.00"));
        when(strategyPort.save(any())).thenReturn(saved);
        when(strategyCyclePort.save(any())).thenReturn(savedCycle);
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(cyclePositionPort).save(argThat(p ->
                p.holdings().compareTo(BigDecimal.valueOf(20)) == 0
                        && p.avgPrice().compareTo(new BigDecimal("95.00")) == 0
                        && p.closingPrice().compareTo(new BigDecimal("100.00")) == 0));
        // 시작금액 = 개장 평가 = 시장가 평가 200 + 20×100 = 2,200 (배수 환산 없음)
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("2200.00")) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.openAmount().compareTo(new BigDecimal("2200.00")) == 0));
        verify(cyclePositionInfiniteDetailPort, never()).save(any());
        assertThat(result.currentHoldings()).isEqualTo(20);
        assertThat(result.divisionCount()).isNull(); // 조회 경로와 동일 — PRIVACY는 분할 수를 싣지 않는다
    }

    @Test
    @DisplayName("VR register() 중간부터 시작 — V값을 시장가×보유수량으로 계산한다 (live 잔고 조회 제거)")
    void register_vr_bootstrap_computesValueFromMarketPrice() {
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, StrategyType.VR,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        RegisterStrategyCommand cmd = vr().deposit("1000").holdings(BigDecimal.valueOf(5)).avgPrice(new BigDecimal("100")).intervalWeeks(4).bandWidth("15.00").recurringAmount(0).build();

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
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("1600.00")) == 0
                && c.openAmount().compareTo(new BigDecimal("1600.00")) == 0));
        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(new BigDecimal("600.00")) == 0));
        assertThat(result.initialDeposit()).isEqualByComparingTo("1000.00");
        assertThat(result.vr().value()).isEqualByComparingTo("600.00");
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("750.00");
        // VR live 잔고 조회는 완전히 제거됨 — BrokerPricePort 시장가 조회 1회로만 V가 계산된다
        verify(brokerPricePort, times(1)).getPrevClose(any(), eq(account.toBrokerRef()));
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — holdings>0인데 avgPrice가 없으면 IllegalArgumentException")
    void register_bootstrap_holdingsWithoutAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).divisionCount(20).holdings(BigDecimal.valueOf(10)).build();

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — avgPrice가 0이어도 holdings>0이면 IllegalArgumentException")
    void register_bootstrap_holdingsWithZeroAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).divisionCount(20).holdings(BigDecimal.valueOf(10)).avgPrice(BigDecimal.ZERO).build();

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — holdings가 음수면 IllegalArgumentException")
    void register_bootstrap_negativeHoldings_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).divisionCount(20).holdings(BigDecimal.valueOf(-1)).build();

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — avgPrice가 음수면 IllegalArgumentException")
    void register_bootstrap_negativeAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).divisionCount(20).avgPrice(new BigDecimal("-5")).build();

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 — 시장가(전일종가) 조회 실패 시 IllegalStateException으로 등록 자체가 실패한다")
    void register_bootstrap_priceFetchFailure_throwsIllegalState() {
        Account account = ownerAccount();
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).divisionCount(20).holdings(BigDecimal.valueOf(10)).avgPrice(new BigDecimal("45.50")).build();

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
        return infinite().ticker(StrategyTicker.SOXL).deposit(new BigDecimal(deposit)).divisionCount(20).build();
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

    // --- register() 예수금 부호·자릿수 (전 전략 타입 공통, CQ-58) ---

    @Test
    @DisplayName("INFINITE·PRIVACY·VR register() 음수 예수금은 거부하고 저장하지 않는다")
    void register_nonDca_rejectsNegativeDeposit() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        for (StrategyType type : List.of(StrategyType.INFINITE, StrategyType.PRIVACY, StrategyType.VR)) {
            assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                    register(type).deposit("-1").build()))
                    .as(type.name()).isInstanceOf(IllegalArgumentException.class).hasMessage("예수금은 0 이상이어야 합니다.");
        }
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("INFINITE·PRIVACY·VR register() 계좌 통화(USD) 자릿수를 넘는 예수금은 거부하고 저장하지 않는다")
    void register_nonDca_rejectsDepositBeyondCurrencyScale() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        for (StrategyType type : List.of(StrategyType.INFINITE, StrategyType.PRIVACY, StrategyType.VR)) {
            assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                    register(type).deposit("100.123").build()))
                    .as(type.name()).isInstanceOf(IllegalArgumentException.class).hasMessage("금액은 소수 2자리까지 입력할 수 있습니다.");
        }
        verify(strategyPort, never()).save(any());
    }

    // --- update() newSeed 검증 (CQ-58 자릿수, CQ-63 INFINITE 최소 시드) ---

    private void stubInfiniteSeedUpdate() {
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000"), new BigDecimal("110"), null, BigDecimal.ZERO, null, null);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyInfiniteDetailPort.findActiveByStrategyId(STRATEGY_ID))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 20)));
        lenient().when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(CYCLE);
        lenient().when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));
    }

    @Test
    @DisplayName("update() INFINITE 시드가 최소 시드(전일종가×분할×2) 미만이면 거부하고 저장하지 않는다")
    void update_infinite_seedBelowMin_rejected() {
        stubInfiniteSeedUpdate();
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenReturn(new BigDecimal("60")); // 최소 2,400

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("2399.99"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("최소 투자금 $2,400.00 이상이 필요합니다.");
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
    }

    @Test
    @DisplayName("update() INFINITE 전일종가 조회가 실패하면 최소 시드 검사만 건너뛰고 수정된다")
    void update_infinite_priceFetchFails_skipsMinCheck() {
        stubInfiniteSeedUpdate();
        when(brokerPricePort.getPrevClose(eq(StrategyTicker.SOXL), any())).thenThrow(new RuntimeException("시세 장애"));

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("100")));

        verify(strategyCyclePort).updateStartAmount(CYCLE_ID, new BigDecimal("100"));
    }

    @Test
    @DisplayName("update() 시드가 계좌 통화 자릿수를 넘으면 거부한다")
    void update_seed_beyondCurrencyScale_rejected() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        lenient().when(brokerPricePort.getPrevClose(any(), any())).thenReturn(new BigDecimal("1"));
        lenient().when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(CYCLE);

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("5000.123"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("금액은 소수 2자리까지 입력할 수 있습니다.");
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verifyNoInteractions(brokerPricePort); // 싼 검증이 외부 시세 호출보다 먼저
    }

    @Test
    @DisplayName("VR register() 신규 등록(보유 0)은 전일종가를 조회하지 않는다")
    void register_vr_does_not_fetch_prev_close() {
        stubSuccessfulRegistration(StrategyType.VR, StrategyTicker.TQQQ);

        strategyService.register(USER_ID, ACCOUNT_ID, vr().deposit("2000").intervalWeeks(4).bandWidth("15.00").recurringAmount(0).build());

        verify(brokerPricePort, never()).getPrevClose(any(), any());
    }

    // --- register() 시작예정일(scheduledStartDate) ---

    @Test
    @DisplayName("INFINITE register() scheduledStartDate 명시 시 저장되는 StrategyCycle.startDate가 그 값과 일치한다")
    void register_infinite_withScheduledStartDate_setsCycleStartDate() {
        stubSuccessfulRegistration(StrategyType.INFINITE, StrategyTicker.SOXL);
        LocalDate scheduledStart = LocalDate.now(FIXED).plusDays(3);
        RegisterStrategyCommand cmd = infinite().ticker(StrategyTicker.SOXL).divisionCount(20).scheduledStartDate(scheduledStart).build();

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
        assertThat(captor.getValue().startDate()).isEqualTo(LocalDate.now(FIXED));
    }

    @Test
    @DisplayName("VR register() scheduledStartDate가 과거 날짜이면 IllegalArgumentException 발생")
    void register_vr_withPastScheduledStartDate_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        LocalDate yesterday = LocalDate.now(FIXED).minusDays(1);
        RegisterStrategyCommand cmd = vr().deposit("1000").intervalWeeks(2).bandWidth("15.00").recurringAmount(0).scheduledStartDate(yesterday).build();

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
        return dca().ticker(ticker).holdings(holdings).avgPrice(avgPrice).dcaConfig(dca).build();
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
                .isInstanceOf(IllegalArgumentException.class).hasMessage("계좌와 종목의 시장이 다릅니다.");
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
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("코인 DCA 주문유형은 시장가만");
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
        assertThat(detail.initialDeposit()).isEqualByComparingTo("0");
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
                .isInstanceOf(IllegalArgumentException.class).hasMessage("존재하지 않는 규칙입니다.");
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
        assertThat(detail.initialDeposit()).isEqualByComparingTo("400"); // 조회 경로와 동일 — 사이클 시작금액(보유 10 × 평단 40)
        verify(cyclePositionPort).save(argThat(p -> p.holdings().compareTo(BigDecimal.TEN) == 0
                && p.deposit().signum() == 0 && p.avgPrice().compareTo(new BigDecimal("40")) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("400")) == 0));
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("주식 DCA 초기 예수금은 시작 원장 예수금이 되고 start_amount에 보유 원가와 함께 합산된다")
    void register_stockDca_storesInitialDeposit() {
        stubDcaRegistration(StrategyTicker.UPRO);
        stubUsCalendar();
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of());

        strategyService.register(USER_ID, ACCOUNT_ID, dca().ticker(StrategyTicker.UPRO).holdings(new BigDecimal("10"))
                .avgPrice(new BigDecimal("40")).deposit(new BigDecimal("1000.50")).dcaConfig(dcaConfig(OrderDirection.BUY)).build());

        verify(cyclePositionPort).save(argThat(p -> p.deposit().compareTo(new BigDecimal("1000.50")) == 0
                && p.holdings().compareTo(BigDecimal.TEN) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("1400.50")) == 0)); // 예수금 + 40 × 10
    }

    @Test
    @DisplayName("DCA 초기 예수금이 음수면 거절한다")
    void register_dca_rejectsNegativeDeposit() {
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(tradingUserProfilePort.findByUserId(USER_ID)).thenReturn(Optional.of(activeUserProfile()));
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dca().ticker(StrategyTicker.UPRO)
                .deposit(new BigDecimal("-1")).dcaConfig(dcaConfig(OrderDirection.BUY)).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("예수금은 0 이상이어야 합니다.");
        verify(strategyPort, never()).save(any());
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
                && p.deposit().signum() == 0 && p.closingPrice() == null));
        // 초기 투입 원가 = 0.01234567 × 90,000,000 = 1,111,110.30
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("1111110.30")) == 0));
        verifyNoInteractions(marginPort, brokerPricePort);
    }

    @Test
    @DisplayName("크립토 DCA 초기 투입 원가는 보유×평단을 소수 2자리 HALF_UP으로 반올림한다")
    void 크립토_DCA_초기원가_2자리_반올림() {
        stubDcaRegistration(StrategyTicker.BTC);
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.BTC)).thenReturn(List.of());

        strategyService.register(USER_ID, ACCOUNT_ID,
                dcaCommand(StrategyTicker.BTC, cryptoDcaConfig(), new BigDecimal("0.30736563"), new BigDecimal("2264.05")));

        // 2264.05 × 0.30736563 = 695.8911...(원문 그대로 반올림)
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(
                new BigDecimal("2264.05").multiply(new BigDecimal("0.30736563")).setScale(2, RoundingMode.HALF_UP)) == 0
                && c.startAmount().scale() == 2));
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
    void validateUniqueTicker_ignores_archived() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO),
                null, null, null, null)));
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of(new Strategy(
                UUID.randomUUID(), ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ARCHIVED, StrategyTicker.UPRO, StrategyCycleSeedType.NONE)));

        // 보관 전략만 있으면 중복이 아니다 — 409가 아닌 다른 경로(이후 단계)로 진행해야 한다
        try {
            strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.SELL)));
        } catch (RuntimeException e) {
            assertThat(e).isNotInstanceOf(DuplicateStrategyTickerException.class);
        }
        // 중복 검사가 실제로 실행됐는지 — 그 전에 다른 예외로 끝나 통과하는 것을 막는다
        verify(strategyPort).findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO);
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
        DcaExecution last = DcaExecution.skipped(STRATEGY_ID, DCA_KEY, OrderDirection.BUY, LocalDate.of(2026, 9, 30),
                DcaSkipReason.CONDITION_NOT_MET, "상세", null);
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
            // 응답 정액 금액은 USD scale 2로 반올림된다(픽스처 "300" → 300.00)
            assertThat(r.rule()).isEqualTo(keyed.rules().getFirst().withSizing(
                    new DcaSizing(com.kista.shared.kernel.DcaSizingType.AMOUNT, new BigDecimal("300.00"), null)));
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
                .isInstanceOf(NoSuchElementException.class);
        verify(strategyPort, never()).save(any());
    }
}
