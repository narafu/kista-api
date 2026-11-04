package com.kista.api.admin.application.service;

import com.kista.shared.support.FixedClockTestConfig;
import java.time.Clock;
import com.kista.shared.contract.account.AccountSummaryResponse;
import com.kista.api.admin.domain.model.AdminStats;
import com.kista.shared.kernel.Broker;
import com.kista.api.admin.application.port.output.*;
import com.kista.api.user.application.port.output.UserPort;
import org.junit.jupiter.api.BeforeEach;
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
import com.kista.shared.kernel.UserStatus;

@ExtendWith(MockitoExtension.class)
class AdminQueryServiceTest {

    @Mock UserPort userPort;
    @Mock AccountQueryPort accountQueryPort;
    @Mock AuditLogPort auditLogPort;
    @Mock TradingQueryPort tradingQueryPort;
    @Mock PrivacyQueryPort privacyQueryPort;
    @Mock AppErrorLogPort appErrorLogPort;

    final Clock clock = FixedClockTestConfig.FIXED; // 고정 시각
    AdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminQueryService(userPort, accountQueryPort, auditLogPort, tradingQueryPort, privacyQueryPort, appErrorLogPort, clock);
    }

    @Test
    void getStats_상태별_카운트를_단일_GROUP_BY_조회로_합산한다() {
        Map<UserStatus, Long> byStatus = new EnumMap<>(UserStatus.class);
        byStatus.put(UserStatus.PENDING, 3L);
        byStatus.put(UserStatus.ACTIVE, 10L);
        byStatus.put(UserStatus.REJECTED, 2L);
        when(userPort.countGroupByStatus()).thenReturn(byStatus);
        when(accountQueryPort.countAll()).thenReturn(7L);

        AdminStats result = service.getStats();

        assertThat(result).isEqualTo(new AdminStats(15L, 3L, 10L, 2L, 7L));
        verify(userPort).countGroupByStatus();
    }

    @Test
    void getStats_특정_상태가_결과에_없으면_0으로_처리한다() {
        // ACTIVE만 존재 — PENDING/REJECTED 상태의 사용자가 아예 없는 경우
        Map<UserStatus, Long> byStatus = new EnumMap<>(UserStatus.class);
        byStatus.put(UserStatus.ACTIVE, 5L);
        when(userPort.countGroupByStatus()).thenReturn(byStatus);
        when(accountQueryPort.countAll()).thenReturn(0L);

        AdminStats result = service.getStats();

        assertThat(result).isEqualTo(new AdminStats(5L, 0L, 5L, 0L, 0L));
    }

    @Test
    void listPrivacyBases_null이면_EPOCH부터_조회() {
        when(privacyQueryPort.findBasesFromTradeDate(LocalDate.EPOCH)).thenReturn(List.of());

        service.listPrivacyBases(null);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(privacyQueryPort).findBasesFromTradeDate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(LocalDate.EPOCH);
    }

    @Test
    void listPrivacyBases_30일이면_KST_30일전_발행분부터_조회() {
        when(privacyQueryPort.findBasesFromTradeDate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        service.listPrivacyBases(30);

        LocalDate expected = LocalDate.now(clock).minusDays(30);
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(privacyQueryPort).findBasesFromTradeDate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(expected);
    }

    @Test
    void listStrategyTradeDates_포트로_위임한다() {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        when(tradingQueryPort.findStrategyTradeDates(accountId, strategyId))
                .thenReturn(List.of(LocalDate.of(2026, 7, 1)));

        List<LocalDate> result = service.listStrategyTradeDates(accountId, strategyId);

        assertThat(result).containsExactly(LocalDate.of(2026, 7, 1));
    }

    @Test
    void listAccounts_포트로_위임한다() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(accountQueryPort.findAll(from, to)).thenReturn(List.of());

        service.listAccounts(from, to);

        verify(accountQueryPort).findAll(from, to);
    }
}
