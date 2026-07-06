package com.kista.application.service.strategy;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.MarginPort;
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
import static org.mockito.ArgumentMatchers.any;
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
    @Mock LiveBalancePort liveBalancePort;                  // VR live 잔고 조회
    @Mock UserSettingsPort userSettingsPort;

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
        VrStrategyLifecycle vrStrategyLifecycle = new VrStrategyLifecycle(strategyVrDetailPort, strategyCycleVrPort);
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
                userSettingsPort);
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
        return new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "appKey", "appSecret", null, Account.Broker.KIS, null);
    }

    private User activeUser() {
        return new User(USER_ID, "kakao123", "테스터",
                User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
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
    @DisplayName("register() 호출 시 같은 계좌에 동일 종목 전략이 이미 있으면 IllegalStateException 발생")
    void register_duplicateTicker_throws() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20,
                null, null, null, null);

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
                null, null, null, null);
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
                null, null, null, null);
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
    }

    // --- VR register() ---

    @Test
    @DisplayName("VR register() 성공 — StrategyVrDetail·StrategyCycleVrDetail 저장, poolLimit 계산, cycleSeedType NONE 강제")
    void register_vr_success_savesVrDetailsAndPoolLimit() {
        // 초기 자산 5000(예수금 2000 + V 3000), poolLimitRate=0.50(recurringAmount=0) → poolLimit = 2500.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("2000"), null, 20,
                new BigDecimal("3000"), 4, new BigDecimal("15.00"), 0);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("2000"), null, null, 0, null, null);
        StrategyCycleVrDetail savedCycleVr = new StrategyCycleVrDetail(
                vrCycleId, new BigDecimal("3000"), 10, new BigDecimal("2500.00"));

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
        // VR 잔고 조회 — holdings=0 (신규 진입)
        when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
        when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("2000")));
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
                cv.poolLimit().compareTo(new BigDecimal("2500.00")) == 0
                        && cv.gradient() == 10));
        // 응답 VrSummary 검증
        assertThat(result.vr()).isNotNull();
        assertThat(result.vr().poolLimit()).isEqualByComparingTo("2500.00");
        assertThat(result.divisionCount()).isNull();  // VR은 divisionCount 없음
        assertThat(result.currentRound()).isNull();    // VR은 currentRound 없음
    }

    @Test
    @DisplayName("VR register() holdings=0 허용 — 신규 진입 시 초기 포지션 저장")
    void register_vr_holdingsZero_allowed() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                new BigDecimal("2000"), 2, new BigDecimal("10.00"), 0);
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
        // live 잔고 holdings=0 (신규 진입)
        when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
        when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000")));
        // holdings=0이어도 예외 없이 등록 성공
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.currentHoldings()).isEqualTo(0);
        // vrInitialSnapshot이 holdings=0으로 저장되는지 검증
        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(vrCycleId) && p.holdings() == 0 && p.avgPrice() == null));
    }

    @Test
    @DisplayName("VR 적립식은 초기 V와 초기 시드가 모두 0이어도 등록 가능")
    void register_vr_recurringDeposit_allowsZeroInitialValueAndSeed() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                BigDecimal.ZERO, 2, new BigDecimal("15.00"), 200);
        Account account = ownerAccount();
        UUID vrStrategyId = UUID.randomUUID();
        UUID vrCycleId = UUID.randomUUID();
        Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
                BigDecimal.ZERO, null, LocalDate.now(), null, null, null);
        CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
                BigDecimal.ZERO, null, null, 0, null, null);
        StrategyCycleVrDetail savedCycleVr = new StrategyCycleVrDetail(
                vrCycleId, BigDecimal.ZERO, 10, new BigDecimal("0.00"));

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
        when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
        when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
                .thenReturn(new AccountBalance(0, null, BigDecimal.ZERO));
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
                BigDecimal.ZERO, 2, new BigDecimal("15.00"), 0);
        RegisterStrategyCommand withdraw = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                BigDecimal.ZERO, 2, new BigDecimal("15.00"), -100);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

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
                new BigDecimal("1000"), 2, new BigDecimal("15.00"), -100);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인출식 VR 전략의 초기 자산");
    }

    @Test
    @DisplayName("VR register() recurringAmount null이면 0으로 저장한다")
    void register_vr_nullRecurringAmount_defaultsToZero() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("2000"), null, 20,
                new BigDecimal("3000"), 4, new BigDecimal("15.00"), null);
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
        when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
        when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("2000")));
        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyVrDetailPort).save(argThat(d -> d.recurringAmount() == 0));
        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimit().compareTo(new BigDecimal("2500.00")) == 0
                        && cv.gradient() == 10));
        assertThat(result.vr().recurringAmount()).isZero();
    }

    @Test
    @DisplayName("VR register() intervalWeeks null이면 IllegalArgumentException 발생")
    void register_vr_missingIntervalWeeks_throws() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("2000"), null, 20,
                new BigDecimal("3000"), null, new BigDecimal("15.00"), 0);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalWeeks");
    }

    @Test
    @DisplayName("VR register() bandWidth null이면 IllegalArgumentException 발생")
    void register_vr_missingBandWidth_throws() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("2000"), null, 20,
                new BigDecimal("3000"), 4, null, 0);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bandWidth");
    }

    @Test
    @DisplayName("VR register() 적립식 initialValue null이면 0으로 정규화한다")
    void register_vr_recurringDeposit_nullInitialValue_defaultsToZero() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
                null, 4, new BigDecimal("15.00"), 200);
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
        when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
        when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
                .thenReturn(new AccountBalance(0, null, BigDecimal.ZERO));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv -> cv.value().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() 적립식 initialUsdDeposit null이면 0으로 정규화한다")
    void register_vr_recurringDeposit_nullPool_defaultsToZero() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, null, null, 20,
                BigDecimal.ZERO, 4, new BigDecimal("15.00"), 200);
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
        when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
        when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
                .thenReturn(new AccountBalance(0, null, BigDecimal.ZERO));

        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv -> cv.poolLimit().compareTo(new BigDecimal("0.00")) == 0));
        verify(cyclePositionPort).save(argThat(p -> p.usdDeposit().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    @DisplayName("VR register() poolLimit scale=2 HALF_UP — recurringAmount > 0 시 poolLimitRate=0.75")
    void register_vr_poolLimitRate_withDeposit() {
        // recurringAmount=100(입금) → poolLimitRate=0.75 → poolLimit = (1000 + 2000) × 0.75 = 2250.00
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
                new BigDecimal("2000"), 4, new BigDecimal("15.00"), 100);
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
        when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
        when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000")));
        strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        verify(strategyCycleVrPort).save(argThat(cv ->
                cv.poolLimit().compareTo(new BigDecimal("2250.00")) == 0
                        && cv.gradient() == 10));
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
                new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        CyclePosition latestPos = new CyclePosition(UUID.randomUUID(), vrCycleId,
                new BigDecimal("2000"), null, null, 0, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(vrVersionId, 4, new BigDecimal("15.00"), 0);
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                vrCycleId, new BigDecimal("3000"), 10, new BigDecimal("1000.00"));

        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(ownerAccount()));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategyId)).thenReturn(Optional.of(vrCycle));
        when(cyclePositionPort.findLatestOneByStrategyId(vrStrategyId)).thenReturn(Optional.of(latestPos));
        when(strategyVrDetailPort.findActiveByStrategyId(vrStrategyId)).thenReturn(Optional.of(vrDetail));
        when(strategyCycleVrPort.findByCycleId(vrCycleId)).thenReturn(Optional.of(cycleVr));

        List<StrategyDetail> result = strategyService.listByUserId(USER_ID);

        assertThat(result).hasSize(1);
        StrategyDetail detail = result.get(0);
        assertThat(detail.divisionCount()).isNull();
        assertThat(detail.currentRound()).isNull();
        assertThat(detail.vr()).isNotNull();
        assertThat(detail.vr().intervalWeeks()).isEqualTo(4);
        assertThat(detail.vr().bandWidth()).isEqualByComparingTo("15.00");
        assertThat(detail.vr().poolLimit()).isEqualByComparingTo("1000.00");
        assertThat(detail.vr().gradient()).isEqualTo(10);
    }
}
