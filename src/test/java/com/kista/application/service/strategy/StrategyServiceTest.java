package com.kista.application.service.strategy;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.application.service.trading.BrokerPriceRouter;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.UpdateStrategyCommand;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock AccountPort accountPort;
    @Mock UserPort userPort;
    @Mock BrokerPriceRouter brokerPriceRouter;
    @Mock BrokerAdapterRegistry registry;
    @Mock MarginPort marginPort;
    @Mock LoadUserSettingsPort loadUserSettingsPort;

    @InjectMocks StrategyService strategyService;

    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID  = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();

    // ACTIVE 상태 전략 픽스처
    private static final Strategy ACTIVE_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.ACTIVE,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE, 20
    );

    // PAUSED 상태 전략 픽스처
    private static final Strategy PAUSED_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.PAUSED,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE, 20
    );

    private static final UUID CYCLE_ID = UUID.randomUUID();

    // 현재 사이클 픽스처 (시작금액 1000)
    private static final StrategyCycle CYCLE = new StrategyCycle(
            CYCLE_ID, STRATEGY_ID, new BigDecimal("1000"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED
    );

    private Account ownerAccount() {
        return new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "appKey", "appSecret", "01", Account.Broker.KIS);
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
        verify(cyclePositionPort, never()).save(any());
    }

    @Test
    @DisplayName("update() 시드 증액(보유 중) — usdDeposit = 새시드 - M, startAmount = 새시드")
    void update_seed_increase_with_holdings_replaces_total_assets() {
        // 보유 10주 @ avgPrice 100 → M = 1000
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), new BigDecimal("110"), new BigDecimal("100"), 10, false, null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        when(cyclePositionPort.findLatestByStrategyId(STRATEGY_ID, 1)).thenReturn(List.of(latest));

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("5000")));

        verify(strategyCyclePort).updateStartAmount(CYCLE_ID, new BigDecimal("5000"));
        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(CYCLE_ID)
                        && p.usdDeposit().compareTo(new BigDecimal("4000")) == 0
                        && p.holdings() == 10
                        && p.avgPrice().compareTo(new BigDecimal("100")) == 0
                        && p.closingPrice().compareTo(new BigDecimal("110")) == 0
        ));
    }

    @Test
    @DisplayName("update() 시드 변경(미보유) — usdDeposit = 새시드, startAmount = 새시드")
    void update_seed_change_with_no_holdings() {
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("1000"), new BigDecimal("110"), null, 0, false, null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        when(cyclePositionPort.findLatestByStrategyId(STRATEGY_ID, 1)).thenReturn(List.of(latest));

        strategyService.update(STRATEGY_ID, USER_ID, new UpdateStrategyCommand(null, new BigDecimal("3000")));

        verify(strategyCyclePort).updateStartAmount(CYCLE_ID, new BigDecimal("3000"));
        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(CYCLE_ID)
                        && p.usdDeposit().compareTo(new BigDecimal("3000")) == 0
                        && p.holdings() == 0
                        && p.avgPrice() == null
        ));
    }

    @Test
    @DisplayName("update() 시드가 매입금액(M)보다 작으면 IllegalArgumentException 발생")
    void update_seed_less_than_purchase_amount_throws() {
        // 보유 10주 @ avgPrice 100 → M = 1000, newSeed=500 < M
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), new BigDecimal("110"), new BigDecimal("100"), 10, false, null, null);

        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
        when(cyclePositionPort.findLatestByStrategyId(STRATEGY_ID, 1)).thenReturn(List.of(latest));

        assertThatThrownBy(() -> strategyService.update(STRATEGY_ID, USER_ID,
                new UpdateStrategyCommand(null, new BigDecimal("500"))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(strategyCyclePort, never()).updateStartAmount(any(), any());
        verify(cyclePositionPort, never()).save(any());
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
        Account accountA = new Account(accountAId, USER_ID, "계좌A", "11111111", "k", "s", "01", Account.Broker.KIS);
        Account accountB = new Account(accountBId, USER_ID, "계좌B", "22222222", "k", "s", "01", Account.Broker.KIS);

        UUID strategyAId = UUID.randomUUID();
        UUID strategyBId = UUID.randomUUID();
        Strategy strategyA = new Strategy(strategyAId, accountAId, Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE, 20);
        Strategy strategyB = new Strategy(strategyBId, accountBId, Strategy.Type.INFINITE, Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE, 20);

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
                Strategy.Type.INFINITE, Strategy.Ticker.SOXL, null, null, 20);

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
                Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, new BigDecimal("600"), null, 20);
        Account account = ownerAccount();
        CyclePosition reservedPosition = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), null, null, 0, false, null, null);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser()); // balanceCheckEnabled=true
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("1000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(ACTIVE_STRATEGY));
        when(cyclePositionPort.findLatestByStrategyId(STRATEGY_ID, 1)).thenReturn(List.of(reservedPosition));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("register() 호출 시 다른 종목 + 예수금 이내 시드면 등록 성공")
    void register_uniqueTickerWithinFreeCash_succeeds() {
        // KIS 가용금액 1000, 기존 SOXL 전략이 500 점유 → 예수금 500 / 신규 시드 500 == 500 → 허용
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(
                Strategy.Type.INFINITE, Strategy.Ticker.TQQQ, new BigDecimal("500"), null, 20);
        Account account = ownerAccount();
        CyclePosition reservedPosition = new CyclePosition(UUID.randomUUID(), CYCLE_ID,
                new BigDecimal("500"), null, null, 0, false, null, null);
        UUID newStrategyId = UUID.randomUUID();
        Strategy savedStrategy = new Strategy(newStrategyId, ACCOUNT_ID, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE, 20);
        StrategyCycle savedCycle = new StrategyCycle(UUID.randomUUID(), newStrategyId,
                new BigDecimal("500"), null, LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED);

        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
        when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser()); // balanceCheckEnabled=true
        when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
        when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("1000"));
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(ACTIVE_STRATEGY));
        when(cyclePositionPort.findLatestByStrategyId(STRATEGY_ID, 1)).thenReturn(List.of(reservedPosition));
        when(strategyPort.save(any(Strategy.class))).thenReturn(savedStrategy);
        when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
        when(brokerPriceRouter.getPrice(Strategy.Ticker.TQQQ, account)).thenReturn(new BigDecimal("50.00"));

        StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

        assertThat(result.strategy().ticker()).isEqualTo(Strategy.Ticker.TQQQ);
        assertThat(result.initialUsdDeposit()).isEqualByComparingTo("500");
        verify(cyclePositionPort).save(argThat(p ->
                p.strategyCycleId().equals(savedCycle.id())
                        && p.usdDeposit().compareTo(new BigDecimal("500")) == 0));
    }
}
