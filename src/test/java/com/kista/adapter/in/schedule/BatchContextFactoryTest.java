package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchContextFactoryTest {

    @Mock AccountPort accountPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock UserPort userPort;
    @Mock NotifyPort notifyPort;
    @InjectMocks BatchContextFactory factory;

    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private Strategy mockStrategy(UUID accountId) {
        return new Strategy(UUID.randomUUID(), accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
    }

    private StrategyCycle mockCycle(UUID strategyId) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, new BigDecimal("1000.00"),
                null, LocalDate.now(), null, Instant.now(), null);
    }

    private Account mockAccount(UUID accountId) {
        return new Account(accountId, USER_ID, "테스트계좌",
                "74420614", "key", "secret", null, Account.Broker.KIS, null);
    }

    private User mockUser() {
        return new User(USER_ID, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    @Test
    void buildAll_returnsContextsForAllStrategies() {
        Strategy strategy = mockStrategy(ACCOUNT_ID);
        StrategyCycle cycle = mockCycle(strategy.id());
        Account account = mockAccount(ACCOUNT_ID);
        User user = mockUser();

        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(cycle));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);

        List<BatchContext> result = factory.buildAll(List.of(strategy));

        assertThat(result).containsExactly(new BatchContext(strategy, cycle, account, user));
    }

    @Test
    void buildAll_emptyStrategies_returnsEmptyList() {
        List<BatchContext> result = factory.buildAll(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void buildAll_contextBuildFails_skipsFailedStrategyAndNotifiesAdmin() {
        // strategy1 계좌 조회 실패 → skip + notifyError, strategy2는 포함
        Strategy strategy1 = mockStrategy(ACCOUNT_ID);
        UUID accountId2 = UUID.randomUUID();
        Strategy strategy2 = mockStrategy(accountId2);
        StrategyCycle cycle2 = mockCycle(strategy2.id());
        Account account2 = mockAccount(accountId2);
        User user = mockUser();

        RuntimeException ex = new RuntimeException("계좌 없음");
        when(strategyCyclePort.findLatestByStrategyId(strategy1.id())).thenReturn(Optional.of(mockCycle(strategy1.id())));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenThrow(ex);
        when(strategyCyclePort.findLatestByStrategyId(strategy2.id())).thenReturn(Optional.of(cycle2));
        when(accountPort.findByIdOrThrow(accountId2)).thenReturn(account2);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);

        List<BatchContext> result = factory.buildAll(List.of(strategy1, strategy2));

        verify(notifyPort).notifyError(ex);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().strategy()).isEqualTo(strategy2);
    }
}
