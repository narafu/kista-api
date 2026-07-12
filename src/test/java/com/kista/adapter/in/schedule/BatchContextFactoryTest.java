package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
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

    private StrategyCycle mockEndedCycle(UUID strategyId) {
        // mockCycle과 동일 패턴 + endAmount/endDate 채움 — 종료된 사이클
        return new StrategyCycle(UUID.randomUUID(), strategyId, new BigDecimal("1000.00"),
                new BigDecimal("1100.00"), LocalDate.now().minusDays(7), LocalDate.now().minusDays(1), Instant.now(), null);
    }

    private Account mockAccount(UUID accountId) {
        return DomainFixtures.kisAccount(accountId, USER_ID);
    }

    private User mockUser() {
        return DomainFixtures.activeUserWithTelegram(USER_ID);
    }

    @Test
    void buildAll_returnsContextsForAllStrategies() {
        Strategy strategy = mockStrategy(ACCOUNT_ID);
        StrategyCycle cycle = mockCycle(strategy.id());
        Account account = mockAccount(ACCOUNT_ID);
        User user = mockUser();

        when(strategyCyclePort.findLatestByStrategyId(strategy.id())).thenReturn(Optional.of(cycle));
        when(accountPort.findAll()).thenReturn(List.of(account));
        when(userPort.findAll()).thenReturn(List.of(user));

        List<BatchContext> result = factory.buildAll(List.of(strategy));

        assertThat(result).containsExactly(new BatchContext(strategy, cycle, account, user));
    }

    @Test
    void buildAll_emptyStrategies_returnsEmptyList() {
        when(accountPort.findAll()).thenReturn(List.of());
        when(userPort.findAll()).thenReturn(List.of());

        List<BatchContext> result = factory.buildAll(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void buildAll_contextBuildFails_skipsFailedStrategyAndNotifiesAdmin() {
        // strategy1 계좌를 배치 조회 결과에서 찾을 수 없음 → skip + notifyError, strategy2는 포함
        Strategy strategy1 = mockStrategy(ACCOUNT_ID);
        UUID accountId2 = UUID.randomUUID();
        Strategy strategy2 = mockStrategy(accountId2);
        StrategyCycle cycle2 = mockCycle(strategy2.id());
        Account account2 = mockAccount(accountId2);
        User user = mockUser();

        when(strategyCyclePort.findLatestByStrategyId(strategy1.id())).thenReturn(Optional.of(mockCycle(strategy1.id())));
        when(strategyCyclePort.findLatestByStrategyId(strategy2.id())).thenReturn(Optional.of(cycle2));
        // account2만 배치 조회 결과에 포함 — strategy1의 ACCOUNT_ID는 조회되지 않아 NoSuchElementException 발생
        when(accountPort.findAll()).thenReturn(List.of(account2));
        when(userPort.findAll()).thenReturn(List.of(user));

        List<BatchContext> result = factory.buildAll(List.of(strategy1, strategy2));

        verify(notifyPort).notifyError(any(NoSuchElementException.class));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().strategy()).isEqualTo(strategy2);
    }

    @Test
    void buildAll_latestCycleAlreadyEnded_skipsAndNotifiesAdmin() {
        // rotation 실패로 새 사이클이 없는 좀비 상태 — 종료 사이클에 주문이 나가면 안 됨
        Strategy strategy = mockStrategy(ACCOUNT_ID);
        when(strategyCyclePort.findLatestByStrategyId(strategy.id()))
                .thenReturn(Optional.of(mockEndedCycle(strategy.id())));
        when(accountPort.findAll()).thenReturn(List.of());
        when(userPort.findAll()).thenReturn(List.of());

        List<BatchContext> result = factory.buildAll(List.of(strategy));

        assertThat(result).isEmpty();
        verify(notifyPort).notifyError(any(IllegalStateException.class));
    }
}
