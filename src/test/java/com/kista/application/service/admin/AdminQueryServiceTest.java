package com.kista.application.service.admin;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.*; import com.kista.trading.domain.port.out.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminQueryServiceTest {

    @Mock UserPort userPort;
    @Mock AccountPort accountPort;
    @Mock OrderPort orderPort;
    @Mock AuditLogPort auditLogPort;
    @Mock StrategyPort strategyPort;
    @Mock PrivacyTradePort privacyTradePort;

    @InjectMocks AdminQueryService service;

    @Test
    void getStats_상태별_카운트를_단일_GROUP_BY_조회로_합산한다() {
        Map<User.UserStatus, Long> byStatus = new EnumMap<>(User.UserStatus.class);
        byStatus.put(User.UserStatus.PENDING, 3L);
        byStatus.put(User.UserStatus.ACTIVE, 10L);
        byStatus.put(User.UserStatus.REJECTED, 2L);
        when(userPort.countGroupByStatus()).thenReturn(byStatus);
        when(accountPort.countAll()).thenReturn(7L);

        AdminStats result = service.getStats();

        assertThat(result).isEqualTo(new AdminStats(15L, 3L, 10L, 2L, 7L));
        verify(userPort).countGroupByStatus();
    }

    @Test
    void getStats_특정_상태가_결과에_없으면_0으로_처리한다() {
        // ACTIVE만 존재 — PENDING/REJECTED 상태의 사용자가 아예 없는 경우
        Map<User.UserStatus, Long> byStatus = new EnumMap<>(User.UserStatus.class);
        byStatus.put(User.UserStatus.ACTIVE, 5L);
        when(userPort.countGroupByStatus()).thenReturn(byStatus);
        when(accountPort.countAll()).thenReturn(0L);

        AdminStats result = service.getStats();

        assertThat(result).isEqualTo(new AdminStats(5L, 0L, 5L, 0L, 0L));
    }

    @Test
    void listPrivacyBases_null이면_EPOCH부터_조회() {
        when(privacyTradePort.findBasesFromTradeDate(LocalDate.EPOCH)).thenReturn(List.of());

        service.listPrivacyBases(null);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(privacyTradePort).findBasesFromTradeDate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(LocalDate.EPOCH);
    }

    @Test
    void listPrivacyBases_30일이면_KST_30일전_발행분부터_조회() {
        when(privacyTradePort.findBasesFromTradeDate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        service.listPrivacyBases(30);

        LocalDate expected = LocalDate.now().minusDays(30);
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(privacyTradePort).findBasesFromTradeDate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(expected);
    }

    @Test
    void listStrategyOrders_계좌ID가_일치하면_전략과_거래일로_주문을_조회한다() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        LocalDate tradeDate = LocalDate.of(2026, 7, 1);
        Strategy strategy = new Strategy(strategyId, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(orderPort.findByStrategyId(strategyId, tradeDate, tradeDate)).thenReturn(List.of());

        service.listStrategyOrders(accountId, strategyId, tradeDate);

        verify(orderPort).findByStrategyId(strategyId, tradeDate, tradeDate);
    }

    @Test
    void listStrategyOrders_다른_계좌의_전략이면_예외() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID otherAccountId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        LocalDate tradeDate = LocalDate.of(2026, 7, 1);
        Strategy strategy = new Strategy(strategyId, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        // 경로의 accountId와 전략의 실제 accountId 불일치 → 404 매핑용 NoSuchElementException
        assertThatThrownBy(() -> service.listStrategyOrders(otherAccountId, strategyId, tradeDate))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listStrategyTradeDates_계좌ID가_일치하면_거래일_목록을_반환한다() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        Strategy strategy = new Strategy(strategyId, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);
        when(orderPort.findTradeDatesByStrategyId(strategyId)).thenReturn(List.of(LocalDate.of(2026, 7, 1)));

        List<LocalDate> result = service.listStrategyTradeDates(accountId, strategyId);

        assertThat(result).containsExactly(LocalDate.of(2026, 7, 1));
    }

    @Test
    void listStrategyTradeDates_다른_계좌의_전략이면_예외() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID otherAccountId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        Strategy strategy = new Strategy(strategyId, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        // 경로의 accountId와 전략의 실제 accountId 불일치 → 404 매핑용 NoSuchElementException
        assertThatThrownBy(() -> service.listStrategyTradeDates(otherAccountId, strategyId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findAccount_ID로_단일_계좌를_조회한다() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        Account account = new Account(accountId, UUID.randomUUID(), "test", "74420614",
                null, null, "01", Account.Broker.KIS, null);
        when(accountPort.findById(accountId)).thenReturn(Optional.of(account));

        Optional<Account> result = service.findAccount(accountId);

        assertThat(result).isPresent().contains(account);
        verify(accountPort).findById(accountId);
    }
}
