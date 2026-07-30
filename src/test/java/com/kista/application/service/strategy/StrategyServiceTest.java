package com.kista.application.service.strategy;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.settings.RecurringMode;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.settings.StrategyFieldSettings;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.strategy.InfiniteCreationResolver;
import com.kista.domain.strategy.PrivacyCreationResolver;
import com.kista.domain.strategy.StrategyCreationResolvers;
import com.kista.domain.strategy.VrCreationResolver;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StrategyService 단위 테스트")
class StrategyServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    @Mock StrategyVrDetailPort strategyVrDetailPort;       // VR 버전 상세 포트
    @Mock StrategyCycleVrPort strategyCycleVrPort;         // VR 사이클 상세 포트
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    @Mock AccountPort accountPort;
    @Mock UserPort userPort;
    @Mock BrokerAdapterRegistry registry;
    @Mock MarginPort marginPort;
    @Mock BrokerPricePort brokerPricePort;                  // 중간부터 시작 — 등록 시점 시장가(전일종가) 조회
    @Mock UserSettingsPort userSettingsPort;
    @Mock RuntimeSettingsPort runtimeSettingsPort;          // 신규 전략 생성 설정 조회

    private StrategyService strategyService;

    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID  = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();
    private static final UUID STRATEGY_VERSION_ID = UUID.randomUUID();

    // ACTIVE 상태 전략 픽스처
    private static final Strategy ACTIVE_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.ACTIVE,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
    );

    // PAUSED 상태 전략 픽스처
    private static final Strategy PAUSED_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.PAUSED,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
    );

    private static final UUID CYCLE_ID = UUID.randomUUID();

    // 현재 사이클 픽스처 (시작금액 1000)
    private static final StrategyCycle CYCLE = new StrategyCycle(
            CYCLE_ID, STRATEGY_ID, STRATEGY_VERSION_ID, new BigDecimal("1000"), null, LocalDate.now(), null, null, null
    );

    @BeforeEach
    void setUp() {
        VrStrategyLifecycle vrStrategyLifecycle = new VrStrategyLifecycle(
                strategyVrDetailPort, strategyCycleVrPort, cyclePositionPort);
        strategyService = new StrategyService(
                strategyPort,
                strategyVersionPort,
                strategyInfiniteDetailPort,
                vrStrategyLifecycle,
                strategyCyclePort,
                cyclePositionPort,
                cyclePositionInfiniteDetailPort,
                accountPort,
                userPort,
                registry,
                userSettingsPort,
                runtimeSettingsPort,
                new StrategyCreationResolvers(List.of(
                        new InfiniteCreationResolver(), new PrivacyCreationResolver(), new VrCreationResolver())));
        lenient().when(runtimeSettingsPort.load()).thenReturn(RuntimeSettings.defaults());
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
        lenient().when(strategyCycleVrPort.findByCycleId(any())).thenReturn(Optional.empty());
        lenient().when(strategyVrDetailPort.findActiveByStrategyId(any())).thenReturn(Optional.empty());
    }

    private Account ownerAccount() {
        return DomainFixtures.kisAccount(ACCOUNT_ID, USER_ID);
    }

    private User activeUser() {
        return DomainFixtures.activeUserWithTelegram(USER_ID);
    }

    private RuntimeSettings settingsWith(Strategy.Type type, StrategyCreationSettings replacement) {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        EnumMap<Strategy.Type, StrategyCreationSettings> strategies = new EnumMap<>(defaults.strategies());
        strategies.put(type, replacement);
        return new RuntimeSettings(defaults.approvalRequired(), defaults.brokers(), strategies);
    }

    private StrategyCreationSettings disabledSettings(Strategy.Type type) {
        StrategyCreationSettings current = RuntimeSettings.defaults().strategies().get(type);
        return new StrategyCreationSettings(false, current.ticker(), current.divisionCount(),
                current.recurringMode(), current.bandWidth(), current.intervalWeeks());
    }

    private Strategy.Ticker defaultTicker(Strategy.Type type) {
        return RuntimeSettings.defaults().strategies().get(type).ticker().defaultValue();
    }

    private RegisterStrategyCommand commandFor(Strategy.Type type, Strategy.Ticker ticker, int divisionCount,
                                                Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount) {
        // 중간부터 시작 입력(initialHoldings/initialAvgPrice) 없음 — holdings=0에서 시작하는 기존 시나리오 전용 헬퍼
        return new RegisterStrategyCommand(type, ticker, null, null, divisionCount,
                null, null, intervalWeeks, bandWidth, recurringAmount,
                null, null, null, null, null, null, null, null, null);
    }

    private void stubSuccessfulRegistration(Strategy.Type type, Strategy.Ticker ticker) {
        Account account = ownerAccount();
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy saved = new Strategy(strategyId, ACCOUNT_ID, type, Strategy.Status.ACTIVE, ticker,
                Strategy.CycleSeedType.NONE);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, ticker)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(strategyPort.save(any())).thenReturn(saved);
        // 저장 인자(특히 startDate)를 그대로 반영 — 고정값 반환 시 scheduledStartDate 검증이 무의미해짐
        when(strategyCyclePort.save(any())).thenAnswer(invocation -> {
            StrategyCycle arg = invocation.getArgument(0);
            return new StrategyCycle(cycleId, arg.strategyId(), arg.strategyVersionId(),
                    arg.startAmount(), arg.endAmount(), arg.startDate(), arg.endDate(),
                    arg.createdAt(), arg.deletedAt());
        });
        when(cyclePositionPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        if (type == Strategy.Type.VR) {
            when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
            when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("1000000"));
            when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        }
    }

    @Test
    @DisplayName("pause() 호출 시 전략 상태가 PAUSED로 저장된다")
    void pause_saves_strategy_as_paused() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        strategyService.pause(STRATEGY_ID, USER_ID);

        verify(strategyPort).save(argThat(s -> s.status() == Strategy.Status.PAUSED));
    }

    @Test
    @DisplayName("resume() 호출 시 전략 상태가 ACTIVE로 저장된다")
    void resume_saves_strategy_as_active() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        strategyService.resume(STRATEGY_ID, USER_ID);

        verify(strategyPort).save(argThat(s -> s.status() == Strategy.Status.ACTIVE));
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
    @DisplayName("update() 호출 시 newSeed가 null이면 cycleSeedType만 변경되고 시드는 변경되지 않는다")
    void update_without_newSeed_only_changes_cycleSeedType() {
        Strategy maintained = ACTIVE_STRATEGY.withCycleSeedType(Strategy.CycleSeedType.MAINTAIN);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(maintained);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));

        StrategyDetail result = strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(Strategy.CycleSeedType.MAINTAIN, null));

        assertThat(result.strategy().cycleSeedType()).isEqualTo(Strategy.CycleSeedType.MAINTAIN);
        assertThat(result.initialUsdDeposit()).isEqualTo(new BigDecimal("1000"));
        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).updateCycleStartSnapshot(any(), any());
    }

    @Test
    @DisplayName("update() holdings가 있으면 시드 수정을 거절한다")
    void update_seed_with_holdings_throws() {
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), new BigDecimal("110"), new BigDecimal("100"), 10, null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
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
                new BigDecimal("1000"), new BigDecimal("110"), null, 0, null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
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
                STRATEGY_ID, ACCOUNT_ID, Strategy.Type.VR, Strategy.Status.ACTIVE,
                Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        CyclePosition opening = new CyclePosition(
                UUID.randomUUID(), CYCLE_ID, new BigDecimal("1000.00"),
                null, null, 0, null, null);
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
        Account accountA = new Account(accountAId, USER_ID, "계좌A", "11111111", "k", "s", null, Account.Broker.KIS, null);
        Account accountB = new Account(accountBId, USER_ID, "계좌B", "22222222", "k", "s", null, Account.Broker.KIS, null);

        UUID strategyAId = UUID.randomUUID();
        UUID strategyBId = UUID.randomUUID();
        Strategy strategyA = new Strategy(strategyAId, accountAId, Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        Strategy strategyB = new Strategy(strategyBId, accountBId, Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(accountA, accountB));
        when(strategyPort.findByAccountId(accountAId)).thenReturn(List.of(strategyA));
        when(strategyPort.findByAccountId(accountBId)).thenReturn(List.of(strategyB));
        when(strategyCyclePort.findLatestByStrategyId(strategyAId)).thenReturn(Optional.of(CYCLE));
        when(strategyCyclePort.findLatestByStrategyId(strategyBId)).thenReturn(Optional.of(CYCLE));

        List<StrategyDetail> result = strategyService.listByUserId(USER_ID);

        assertThat(result).extracting(d -> d.strategy().id())
                .containsExactlyInAnyOrder(strategyAId, strategyBId);
    }

    // --- register() ---

    @Test
    @DisplayName("register()는 비활성화된 각 전략 유형의 신규 생성을 거부한다")
    void register_disabledStrategyType_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        for (Strategy.Type type : Strategy.Type.values()) {
            when(runtimeSettingsPort.load()).thenReturn(settingsWith(type, disabledSettings(type)));
            RegisterStrategyCommand cmd = commandFor(type, defaultTicker(type), 20, 2, new BigDecimal("15"), 0);

            assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("비활성화");
        }

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("INFINITE register()는 생략한 ticker와 divisionCount에 런타임 기본값을 적용한다")
    void register_infinite_appliesOmittedDefaults() {
        stubSuccessfulRegistration(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ);
        StrategyCreationSettings customized = new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(Strategy.Ticker.SOXL, Strategy.Ticker.TQQQ), Strategy.Ticker.TQQQ),
                new StrategyFieldSettings<>(true, List.of(30, 40), 30), null, null, null);
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Strategy.Type.INFINITE, customized));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.INFINITE, null, 0, null, null, null));

        assertThat(result.strategy().ticker()).isEqualTo(Strategy.Ticker.TQQQ);
        assertThat(result.divisionCount()).isEqualTo(30);
        verify(strategyInfiniteDetailPort).save(argThat(detail -> detail.divisionCount() == 30));
    }

    @Test
    @DisplayName("INFINITE register()는 ticker와 divisionCount 허용 목록 밖의 값을 거부한다")
    void register_infinite_rejectsDisallowedFields() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings infinite = RuntimeSettings.defaults().strategies().get(Strategy.Type.INFINITE);
        StrategyCreationSettings restricted = new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(Strategy.Ticker.SOXL), Strategy.Ticker.SOXL),
                infinite.divisionCount(), null, null, null);
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Strategy.Type.INFINITE, restricted));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.INFINITE, Strategy.Ticker.MAGX, 20, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.INFINITE, Strategy.Ticker.SOXL, 25, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("INFINITE 고정 ticker와 divisionCount는 명시적 기본값만 허용한다")
    void register_infinite_nonCustomizableFields_allowDefaultRejectOther() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings fixed = new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(false, List.of(Strategy.Ticker.SOXL), Strategy.Ticker.SOXL),
                new StrategyFieldSettings<>(false, List.of(30), 30), null, null, null);
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Strategy.Type.INFINITE, fixed));
        when(strategyPort.existsByAccountIdAndTicker(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.INFINITE, Strategy.Ticker.SOXL, 30, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 해당 종목");
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, 30, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.INFINITE, Strategy.Ticker.SOXL, 40, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("INFINITE register()는 기본 설정의 divisionCount 30과 40을 계속 허용한다")
    void register_infinite_preservesDivisionCountThirtyAndForty() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.existsByAccountIdAndTicker(any(), any())).thenReturn(true);

        for (int divisionCount : List.of(30, 40)) {
            assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                    commandFor(Strategy.Type.INFINITE, Strategy.Ticker.SOXL, divisionCount, null, null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("이미 해당 종목");
        }
    }

    @Test
    @DisplayName("PRIVACY와 VR register()는 고정 ticker와 다른 명시 입력을 거부한다")
    void register_fixedTickerTypes_rejectExplicitNonDefault() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.PRIVACY, Strategy.Ticker.TQQQ, 20, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, Strategy.Ticker.SOXL, 20, 2, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VR register()는 생략한 ticker, recurring mode, bandWidth, intervalWeeks 기본값을 적용한다")
    void register_vr_appliesOmittedDefaults() {
        stubSuccessfulRegistration(Strategy.Type.VR, Strategy.Ticker.TQQQ);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID,
                new RegisterStrategyCommand(Strategy.Type.VR, null, new BigDecimal("1000"), null, 0,
                        null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));

        assertThat(result.strategy().ticker()).isEqualTo(Strategy.Ticker.TQQQ);
        verify(strategyVrDetailPort).save(argThat(detail -> detail.intervalWeeks() == 2
                && detail.bandWidth().compareTo(new BigDecimal("15")) == 0
                && detail.recurringAmount() == 0));
    }

    @Test
    @DisplayName("VR register()는 bandWidth와 intervalWeeks 허용 목록 밖의 값을 거부한다")
    void register_vr_rejectsDisallowedBandWidthAndInterval() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 20, 2, new BigDecimal("12"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 20, 3, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VR 고정 bandWidth와 intervalWeeks는 명시적 기본값만 허용한다")
    void register_vr_nonCustomizableBandAndInterval_allowDefaultRejectOther() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings vr = RuntimeSettings.defaults().strategies().get(Strategy.Type.VR);
        StrategyCreationSettings fixed = new StrategyCreationSettings(true, vr.ticker(), null, vr.recurringMode(),
                new StrategyFieldSettings<>(false, List.of(new BigDecimal("15")), new BigDecimal("15")),
                new StrategyFieldSettings<>(false, List.of(2), 2));
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Strategy.Type.VR, fixed));
        when(strategyPort.existsByAccountIdAndTicker(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 0, 2, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 해당 종목");
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 0, 2, new BigDecimal("10"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 0, 4, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VR recurringMode 허용 목록은 recurringAmount 부호로 검증하고 금액 크기는 제한하지 않는다")
    void register_vr_validatesRecurringModeBySignOnly() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings vr = RuntimeSettings.defaults().strategies().get(Strategy.Type.VR);
        StrategyCreationSettings depositOnly = new StrategyCreationSettings(true, vr.ticker(), null,
                new StrategyFieldSettings<>(true, List.of(RecurringMode.DEPOSIT), RecurringMode.DEPOSIT),
                vr.bandWidth(), vr.intervalWeeks());
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Strategy.Type.VR, depositOnly));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 20, 2, new BigDecimal("15"), -999)))
                .isInstanceOf(IllegalArgumentException.class);
        when(strategyPort.existsByAccountIdAndTicker(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 20, 2, new BigDecimal("15"), 999)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 해당 종목");
    }

    @Test
    @DisplayName("VR recurringMode가 고정이면 생략/0은 허용하고 명시적 nonzero는 거부한다")
    void register_vr_nonCustomizableRecurringMode_forcesHold() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        StrategyCreationSettings vr = RuntimeSettings.defaults().strategies().get(Strategy.Type.VR);
        StrategyCreationSettings holdOnly = new StrategyCreationSettings(true, vr.ticker(), null,
                new StrategyFieldSettings<>(false, List.of(RecurringMode.HOLD), RecurringMode.HOLD),
                vr.bandWidth(), vr.intervalWeeks());
        when(runtimeSettingsPort.load()).thenReturn(settingsWith(Strategy.Type.VR, holdOnly));
        when(strategyPort.existsByAccountIdAndTicker(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 20, 2, new BigDecimal("15"), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 20, 2, new BigDecimal("15"), 0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.VR, null, 20, 2, new BigDecimal("15"), 1)))
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

        verifyNoInteractions(runtimeSettingsPort);
    }

    @Test
    @DisplayName("register() 호출 시 같은 계좌에 동일 종목 전략이 이미 있으면 IllegalStateException 발생")
    void register_duplicateTicker_throws() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.SOXL)).thenReturn(true);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalStateException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register() 호출 시 시드가 예수금(KIS 가용금액 - 기존 전략 점유 시드)을 초과하면 IllegalArgumentException 발생")
    void register_seedExceedsFreeCash_throws() {
        // KIS 가용금액 1000, 기존 SOXL 전략이 500 점유 → 예수금 500 / 신규 시드 600 > 500
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, new BigDecimal("600"), null, 20,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        CyclePosition reservedPosition = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), null, null, 0, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID)); // balanceCheckEnabled=true
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("1000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(ACTIVE_STRATEGY));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(reservedPosition));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register() 호출 시 다른 종목 + 예수금 이내 시드면 등록 성공")
    void register_uniqueTickerWithinFreeCash_succeeds() {
        // KIS 가용금액 1000, 기존 SOXL 전략이 500 점유 → 예수금 500 / 신규 시드 500 == 500 → 허용
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, new BigDecimal("500"), null, 40,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        CyclePosition reservedPosition = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), null, null, 0, null, null);
        UUID newStrategyId = UUID.randomUUID();
        Strategy savedStrategy = new Strategy(newStrategyId, ACCOUNT_ID, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(UUID.randomUUID(), newStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("500"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), savedCycle.id(),
                new BigDecimal("500"), null, null, 0, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID)); // balanceCheckEnabled=true
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("1000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(ACTIVE_STRATEGY));
        when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(reservedPosition));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.strategy().ticker()).isEqualTo(Strategy.Ticker.TQQQ);
        assertThat(result.initialUsdDeposit()).isEqualByComparingTo("500");
        assertThat(result.divisionCount()).isEqualTo(40);
        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(savedCycle.id())
                        && p.usdDeposit().compareTo(new BigDecimal("500")) == 0
                        && p.closingPrice() == null));
        // holdings=0 하위호환 — 중간부터 시작 입력이 없으면 시장가 조회 자체를 건너뛴다
        verify(registry, never()).require(any(), eq(BrokerPricePort.class));
    }

    // --- VR register() ---

    @Test
    @DisplayName("VR register() holdings=0이어도 초기 V 입력이 있으면 그 값을 V값으로 저장한다")
    void register_vr_explicitInitialValue_holdingsZero_usesOverrideAsValue() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                null, null, 2, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("5000"));
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                BigDecimal.ZERO, null, null, 0, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // holdings=0이라 실제 포지션·startAmount는 그대로 0이지만, V값 저장은 override(5000)를 사용한다
        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(new BigDecimal("5000")) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().signum() == 0));
        verify(registry, never()).require(any(), eq(BrokerPricePort.class));
    }

    @Test
    @DisplayName("VR register() 초기 V 입력이 있어도 실제 포지션·startAmount는 시장가 평가금 기준을 그대로 유지한다")
    void register_vr_explicitInitialValue_withHoldings_keepsEvaluatedAssetsSeparate() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                10, new BigDecimal("100.00"), 2, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("99999"));
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, BrokerPricePort.class)).thenReturn(brokerPricePort);
        when(brokerPricePort.getPrevClose(Strategy.Ticker.TQQQ, account)).thenReturn(new BigDecimal("50.00"));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
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
                p.holdings() == 10 && p.avgPrice().compareTo(new BigDecimal("100.00")) == 0));
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("500.00")) == 0));
    }

    @Test
    @DisplayName("VR register() 인출식 최소자산 검증은 초기 V 입력을 반영하지 않고 실제 평가금 기준으로만 판단한다")
    void register_vr_explicitInitialValue_doesNotBypassWithdrawalMinimumCheck() {
        // 게이트(V+예수금>0)는 override(100000)로 통과하지만, 인출식 최소자산(실제 평가금 0 + 예수금 100)은 미달
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("100"), null, 20,
                null, null, 2, new BigDecimal("15.00"), -100,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("100000"));
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인출식 VR 전략의 초기 자산");
    }

    @Test
    @DisplayName("VR register() 초기 V값이 음수이면 IllegalArgumentException")
    void register_vr_negativeInitialValue_throws() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, null, null, 20,
                null, null, 2, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("-1"));
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초기 V값(initialVrValue)은 0 이상이어야 합니다");
    }

    @Test
    @DisplayName("VR register() 성공 — StrategyVrDetail·StrategyCycleVrDetail 저장, poolLimit 계산, cycleSeedType NONE 강제")
    void register_vr_success_savesVrDetailsAndPoolLimit() {
        // holdings=0(중간부터 시작 미입력) → V=0, 초기 자산 2000(예수금만), poolLimitRate=0.50(recurringAmount=0) → poolLimit = 1000.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("2000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("2000"), null, null, 0, null, null);
        // recurringAmount=0 → 기본 poolLimitRate=0.50 (거치식) — poolLimit 달러 파생은 개장 pool(2000)×0.50=1000.00
        StrategyCycleVrDetail savedCycleVr = new StrategyCycleVrDetail(
                vrCycleId, BigDecimal.ZERO, 10, new BigDecimal("0.50"));

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class))).thenReturn(savedCycleVr);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // cycleSeedType NONE 강제 + VR → TQQQ 강제 검증
        verify(strategyPort).save(argThat(s ->
                s.type() == Strategy.Type.VR && s.ticker() == Strategy.Ticker.TQQQ
                        && s.cycleSeedType() == Strategy.CycleSeedType.NONE));
        // StrategyVrDetail 저장 검증
        verify(strategyVrDetailPort).save(argThat(d ->
                d.intervalWeeks() == 4
                        && d.bandWidth().compareTo(new BigDecimal("15.00")) == 0
                        && d.recurringAmount() == 0));
        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimitRate().compareTo(new BigDecimal("0.50")) == 0
                        && cv.gradient() == 10));
        // 등록 시점 중간부터 시작 미입력이면 시장가 조회를 건너뛴다
        verify(registry, never()).require(any(), eq(BrokerPricePort.class));
        // 응답 VrSummary 검증
        assertThat(result.vr()).isNotNull();
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("1000.00");
        assertThat(result.divisionCount()).isNull();  // VR은 divisionCount 없음
        assertThat(result.currentRound()).isNull();    // VR은 currentRound 없음
    }

    @Test
    @DisplayName("VR register() holdings=0 허용 — 중간부터 시작 미입력 시 initialSnapshot으로 초기 포지션 저장")
    void register_vr_holdingsZero_allowed() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 2, new BigDecimal("10.00"), 0,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("1000"), null, null, 0, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("3000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        // holdings=0이어도 예외 없이 등록 성공
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.currentHoldings()).isEqualTo(0);
        // holdings=0 → CyclePosition.initialSnapshot(closingPrice도 null) 사용 검증, 시장가 조회는 건너뛴다
        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(vrCycleId) && p.holdings() == 0
                        && p.avgPrice() == null && p.closingPrice() == null));
        verify(registry, never()).require(any(), eq(BrokerPricePort.class));
    }

    @Test
    @DisplayName("VR 적립식은 초기 V와 초기 시드가 모두 0이어도 등록 가능")
    void register_vr_recurringDeposit_allowsZeroInitialValueAndSeed() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                null, null, 2, new BigDecimal("15.00"), 200,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                BigDecimal.ZERO, null, null, 0, null, null);
        // recurringAmount=200(적립식) → 기본 poolLimitRate=0.75, 단 개장 pool=0이므로 파생 poolLimit은 0.00
        StrategyCycleVrDetail savedCycleVr = new StrategyCycleVrDetail(
                vrCycleId, BigDecimal.ZERO, 10, new BigDecimal("0.75"));

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class))).thenReturn(savedCycleVr);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.vr()).isNotNull();
        assertThat(result.vr().value()).isEqualByComparingTo("0");
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("VR 거치식과 인출식은 초기 V와 초기 시드가 모두 0이면 등록 불가")
    void register_vr_nonDeposit_requiresInitialValueOrSeed() {
        RegisterStrategyCommand hold = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                null, null, 2, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null, null);
        RegisterStrategyCommand withdraw = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                null, null, 2, new BigDecimal("15.00"), -100,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        // 검증 순서상 잔고 검증(validateBalanceIfRequired)이 VR 파라미터 검증보다 먼저 실행된다
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(BigDecimal.ZERO);
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());

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
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 2, new BigDecimal("15.00"), -100,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        // 검증 순서상 잔고 검증(validateBalanceIfRequired)이 VR 파라미터 검증보다 먼저 실행된다
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인출식 VR 전략의 초기 자산");
    }

    @Test
    @DisplayName("VR register() recurringAmount null이면 0으로 저장한다")
    void register_vr_nullRecurringAmount_defaultsToZero() {
        // holdings=0 → V=0, 초기 자산 2000(예수금만), poolLimitRate=0.50 → poolLimit = 1000.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("2000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), null,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("2000"), null, null, 0, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d -> d.recurringAmount() == 0));
        // recurringAmount=0(거치식) → 기본 poolLimitRate=0.50, poolLimit 달러 파생값 = 개장 pool(2000)×0.50 = 1000.00
        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimitRate().compareTo(new BigDecimal("0.50")) == 0
                        && cv.gradient() == 10));
        assertThat(result.vr().recurringAmount()).isZero();
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("VR register() 적립식 initialValue null이면 0으로 정규화한다")
    void register_vr_recurringDeposit_nullInitialValue_defaultsToZero() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                null, null, 4, new BigDecimal("15.00"), 200,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenAnswer(inv -> inv.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() 적립식 initialUsdDeposit null이면 0으로 정규화한다")
    void register_vr_recurringDeposit_nullPool_defaultsToZero() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, null, null, 20,
                null, null, 4, new BigDecimal("15.00"), 200,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenAnswer(inv -> inv.getArgument(0));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // recurringAmount=200(적립식) → 기본 poolLimitRate=0.75 (개장 pool=0이라 달러 파생 poolLimit은 0이지만 저장 비율 자체는 0.75)
        verify(strategyCycleVrPort).save(argThat(cv -> cv.poolLimitRate().compareTo(new BigDecimal("0.75")) == 0));
        verify(cyclePositionPort).save(argThat(p -> p.usdDeposit().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() poolLimit scale=2 HALF_UP — recurringAmount > 0 시 poolLimitRate=0.75")
    void register_vr_poolLimitRate_withDeposit() {
        // holdings=0 → V=0, recurringAmount=100(입금) → poolLimitRate=0.75 → poolLimit = 1000 × 0.75 = 750.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 100,
                null, null, null, null, null, null, null, null, null);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("1000"), null, null, 0, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("3000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimitRate().compareTo(new BigDecimal("0.75")) == 0
                        && cv.gradient() == 10));
        // poolLimit(달러) 파생값 검증 — 개장 pool(1000) × poolLimitRate(0.75) = 750.00, scale=2 HALF_UP
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("750.00");
    }

    // --- VR register() 램프 파라미터 정규화·검증 (경과주수 기반 램프 신규 기능) ---

    @Test
    @DisplayName("VR register() 램프 8필드 모두 생략 — 거치식(recurringAmount=0) 부호 파생 기본값 저장")
    void register_vr_rampFieldsOmitted_appliesNormalizedDefaults() {
        stubSuccessfulRegistration(Strategy.Type.VR, Strategy.Ticker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null,
                null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // recurringAmount=0(거치식) → initialGradient=10, initialPoolLimitRate=0.50
        // gMax/poolLimitFloor 미지정 시 initial* 값을 그대로 사용해 램프가 no-op이 되는지 확인
        verify(strategyVrDetailPort).save(argThat(d ->
                d.initialGradient() == 10 && d.gGraceWeeks() == 52 && d.gStepWeeks() == 26 && d.gMax() == 10
                        && d.initialPoolLimitRate().compareTo(new BigDecimal("0.50")) == 0
                        && d.pGraceWeeks() == 52 && d.pStepWeeks() == 26
                        && d.poolLimitFloor().compareTo(new BigDecimal("0.50")) == 0));
    }

    @Test
    @DisplayName("VR register() 램프 8필드 모두 생략 — 인출식(recurringAmount<0)은 initialGradient=20, initialPoolLimitRate=0.25 기본값 적용")
    void register_vr_rampFieldsOmitted_withdrawal_appliesGradient20() {
        stubSuccessfulRegistration(Strategy.Type.VR, Strategy.Ticker.TQQQ);
        // 인출식: recurringAmount=-100, intervalWeeks=2 → 필요 최소자산 = 100×100×4/2 = 20000, initialUsdDeposit=20000으로 충족
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("20000"), null, 20,
                null, null, 2, new BigDecimal("15.00"), -100,
                null, null, null, null, null, null, null, null,
                null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.initialGradient() == 20 && d.gMax() == 20
                        && d.initialPoolLimitRate().compareTo(new BigDecimal("0.25")) == 0
                        && d.poolLimitFloor().compareTo(new BigDecimal("0.25")) == 0));
    }

    @Test
    @DisplayName("VR register() 램프 8필드 명시 입력 시 정규화 없이 그대로 저장된다")
    void register_vr_rampFieldsExplicit_savedAsProvided() {
        stubSuccessfulRegistration(Strategy.Type.VR, Strategy.Ticker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                12, 10, 5, 18,
                new BigDecimal("0.60"), 8, 4, new BigDecimal("0.30"),
                null);

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
        stubSuccessfulRegistration(Strategy.Type.VR, Strategy.Ticker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                null, null, null, null,
                new BigDecimal("0.60"), 0, 0, BigDecimal.ZERO,
                null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.pStepWeeks() == 0 && d.pGraceWeeks() == 0
                        && d.poolLimitFloor().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() gStepWeeks=0 — gradient 램프 비활성화, gGraceWeeks=0·gMax=0을 그대로 저장")
    void register_vr_gStepWeeksZero_disablesRampAndSkipsMaxValidation() {
        stubSuccessfulRegistration(Strategy.Type.VR, Strategy.Ticker.TQQQ);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                10, 0, 0, 0,
                null, null, null, null,
                null);

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d ->
                d.gStepWeeks() == 0 && d.gGraceWeeks() == 0 && d.gMax() == 0));
    }

    @Test
    @DisplayName("VR register() pStepWeeks=0이어도 poolLimitFloor > initialPoolLimitRate이면 IllegalArgumentException (DB CHECK 위반으로 새는 것 방지)")
    void register_vr_pStepWeeksZero_poolLimitFloorExceedsInitial_stillThrows() {
        Account account = ownerAccount();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                null, null, null, null,
                new BigDecimal("0.60"), 0, 0, new BigDecimal("0.90"),
                null);

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
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                15, null, null, 10,
                null, null, null, null,
                null);

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
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 4, new BigDecimal("15.00"), 0,
                null, null, null, null,
                new BigDecimal("0.50"), null, null, new BigDecimal("0.80"),
                null);

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
        Strategy vrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(vrCycleId, vrStrategyId, vrVersionId,
                new BigDecimal("1600"), null, LocalDate.now(), null, null, null);
        CyclePosition latestPos = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("700"), null, null, 0, null, null);
        CyclePosition openingPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("1000"), null, null, 0, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(vrVersionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        // 총 시작금액 1600과 개장 USD pool 1000은 분리된다. poolLimit은 1000 × 0.50 = 500.00이다.
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                vrCycleId, new BigDecimal("3000"), 10, new BigDecimal("0.50"));

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategyId)).thenReturn(Optional.of(vrCycle));
        when(cyclePositionPort.findLatestOneByStrategyId(vrStrategyId)).thenReturn(Optional.of(latestPos));
        when(cyclePositionPort.findFirstOne(vrCycleId)).thenReturn(Optional.of(openingPosition));
        when(strategyVrDetailPort.findActiveByStrategyId(vrStrategyId)).thenReturn(Optional.of(vrDetail));
        when(strategyCycleVrPort.findByCycleId(vrCycleId)).thenReturn(Optional.of(cycleVr));

        List<StrategyDetail> result = strategyService.listByUserId(USER_ID);

        assertThat(result).hasSize(1);
        StrategyDetail detail = result.get(0);
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
                vrStrategyId, ACCOUNT_ID, Strategy.Type.VR, Strategy.Status.ACTIVE,
                Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(
                vrCycleId, vrStrategyId, UUID.randomUUID(), new BigDecimal("1600.00"),
                null, LocalDate.now(), null, null, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategyId)).thenReturn(Optional.of(vrCycle));

        assertThatThrownBy(() -> strategyService.listByUserId(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션 없음")
                .hasMessageContaining(vrCycleId.toString());
    }

    // --- register() 중간부터 시작 (bootstrap position) ---

    @Test
    @DisplayName("INFINITE register() 중간부터 시작 — 시장가 조회로 startAmount·초기 포지션을 holdings/avgPrice로 seed한다")
    void register_infinite_bootstrap_seedsPositionFromMarketPrice() {
        Account account = ownerAccount();
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy saved = new Strategy(strategyId, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.ACTIVE,
                Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(cycleId, strategyId, STRATEGY_VERSION_ID,
                new BigDecimal("1500.00"), null, LocalDate.now(), null, null, null);

        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, new BigDecimal("1000"), null, 20,
                10, new BigDecimal("45.50"), null, null, null,
                null, null, null, null, null, null, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.SOXL)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("2000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(registry.require(account, BrokerPricePort.class)).thenReturn(brokerPricePort);
        when(brokerPricePort.getPrevClose(Strategy.Ticker.SOXL, account)).thenReturn(new BigDecimal("50.00"));
        when(strategyPort.save(any())).thenReturn(saved);
        when(strategyCyclePort.save(any())).thenReturn(savedCycle);
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // startAmount = 현금 1000 + 시장가 50.00×10 = 1500.00
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("1500.00")) == 0));
        verify(cyclePositionPort).save(argThat(p ->
                p.holdings() == 10
                        && p.avgPrice().compareTo(new BigDecimal("45.50")) == 0
                        && p.closingPrice().compareTo(new BigDecimal("50.00")) == 0
                        && p.usdDeposit().compareTo(new BigDecimal("1000")) == 0));
        verify(cyclePositionInfiniteDetailPort).save(argThat(d -> !d.isReverseMode()));
        assertThat(result.currentHoldings()).isEqualTo(10);
    }

    @Test
    @DisplayName("PRIVACY register() 중간부터 시작 — CyclePositionInfiniteDetail을 저장하지 않는다")
    void register_privacy_bootstrap_savesPositionWithoutInfiniteDetail() {
        Account account = ownerAccount();
        Strategy.Ticker ticker = defaultTicker(Strategy.Type.PRIVACY);
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Strategy saved = new Strategy(strategyId, ACCOUNT_ID, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE,
                ticker, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(cycleId, strategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2200.00"), null, LocalDate.now(), null, null, null);

        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.PRIVACY, null, new BigDecimal("200"), null, 0,
                20, new BigDecimal("95.00"), null, null, null,
                null, null, null, null, null, null, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, ticker)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(registry.require(account, BrokerPricePort.class)).thenReturn(brokerPricePort);
        when(brokerPricePort.getPrevClose(ticker, account)).thenReturn(new BigDecimal("100.00"));
        when(strategyPort.save(any())).thenReturn(saved);
        when(strategyCyclePort.save(any())).thenReturn(savedCycle);
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(cyclePositionPort).save(argThat(p ->
                p.holdings() == 20
                        && p.avgPrice().compareTo(new BigDecimal("95.00")) == 0
                        && p.closingPrice().compareTo(new BigDecimal("100.00")) == 0));
        verify(cyclePositionInfiniteDetailPort, never()).save(any());
        assertThat(result.currentHoldings()).isEqualTo(20);
    }

    @Test
    @DisplayName("VR register() 중간부터 시작 — V값을 시장가×보유수량으로 계산한다 (live 잔고 조회 제거)")
    void register_vr_bootstrap_computesValueFromMarketPrice() {
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                5, new BigDecimal("100"), 4, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(registry.require(account, BrokerPricePort.class)).thenReturn(brokerPricePort);
        when(brokerPricePort.getPrevClose(Strategy.Ticker.TQQQ, account)).thenReturn(new BigDecimal("120"));
        when(strategyPort.save(any())).thenReturn(savedVrStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenAnswer(invocation -> {
            StrategyCycle cycle = invocation.getArgument(0);
            return new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                    cycle.startAmount(), null, cycle.startDate(), null, null, null);
        });
        when(cyclePositionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        // startAmount = USD pool 1000 + 시장가 120 × 보유수량 5 = 1600.00, V = 600.00
        verify(strategyCyclePort).save(argThat(c -> c.startAmount().compareTo(new BigDecimal("1600.00")) == 0));
        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(new BigDecimal("600.00")) == 0));
        assertThat(result.initialUsdDeposit()).isEqualByComparingTo("1000.00");
        assertThat(result.vr().value()).isEqualByComparingTo("600.00");
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("500.00");
        // VR live 잔고 조회는 완전히 제거됨 — BrokerPricePort 시장가 조회 1회로만 V가 계산된다
        verify(registry, times(1)).require(account, BrokerPricePort.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — holdings>0인데 avgPrice가 없으면 IllegalArgumentException")
    void register_bootstrap_holdingsWithoutAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                10, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
        verify(registry, never()).require(any(), any());
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — avgPrice가 0이어도 holdings>0이면 IllegalArgumentException")
    void register_bootstrap_holdingsWithZeroAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                10, BigDecimal.ZERO, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — holdings가 음수면 IllegalArgumentException")
    void register_bootstrap_negativeHoldings_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                -1, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 검증 — avgPrice가 음수면 IllegalArgumentException")
    void register_bootstrap_negativeAvgPrice_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                null, new BigDecimal("-5"), null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register() 중간부터 시작 — 시장가(전일종가) 조회 실패 시 IllegalStateException으로 등록 자체가 실패한다")
    void register_bootstrap_priceFetchFailure_throwsIllegalState() {
        Account account = ownerAccount();
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                10, new BigDecimal("45.50"), null, null, null,
                null, null, null, null, null, null, null, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.SOXL)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        when(registry.require(account, BrokerPricePort.class)).thenReturn(brokerPricePort);
        when(brokerPricePort.getPrevClose(Strategy.Ticker.SOXL, account))
                .thenThrow(new RuntimeException("증권사 API 조회 실패"));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalStateException.class);
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register() holdings=0 하위호환 — 중간부터 시작 미입력 시 시장가 조회 없이 기존 initialSnapshot으로 등록된다")
    void register_holdingsZero_backwardCompatible_skipsMarketPriceFetch() {
        stubSuccessfulRegistration(Strategy.Type.INFINITE, Strategy.Ticker.SOXL);

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.INFINITE, Strategy.Ticker.SOXL, 20, null, null, null));

        assertThat(result.currentHoldings()).isEqualTo(0);
        verify(registry, never()).require(any(), eq(BrokerPricePort.class));
        verify(cyclePositionPort).save(argThat(p -> p.closingPrice() == null && p.avgPrice() == null));
    }

    // --- register() 시작예정일(scheduledStartDate) ---

    @Test
    @DisplayName("INFINITE register() scheduledStartDate 명시 시 저장되는 StrategyCycle.startDate가 그 값과 일치한다")
    void register_infinite_withScheduledStartDate_setsCycleStartDate() {
        stubSuccessfulRegistration(Strategy.Type.INFINITE, Strategy.Ticker.SOXL);
        LocalDate scheduledStart = LocalDate.now(TimeZones.KST).plusDays(3);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, scheduledStart);

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
        Strategy.Ticker ticker = defaultTicker(Strategy.Type.PRIVACY);
        stubSuccessfulRegistration(Strategy.Type.PRIVACY, ticker);

        // commandFor()는 scheduledStartDate 자리에 null을 전달 — 생략 시나리오
        strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(Strategy.Type.PRIVACY, null, 0, null, null, null));

        ArgumentCaptor<StrategyCycle> captor = ArgumentCaptor.forClass(StrategyCycle.class);
        verify(strategyCyclePort).save(captor.capture());
        assertThat(captor.getValue().startDate()).isEqualTo(LocalDate.now(TimeZones.KST));
    }

    @Test
    @DisplayName("VR register() scheduledStartDate가 과거 날짜이면 IllegalArgumentException 발생")
    void register_vr_withPastScheduledStartDate_throws() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        LocalDate yesterday = LocalDate.now(TimeZones.KST).minusDays(1);
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                null, null, 2, new BigDecimal("15.00"), 0,
                null, null, null, null, null, null, null, null, yesterday);

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작예정일");

        verify(strategyPort, never()).save(any());
    }
}
