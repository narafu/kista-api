package com.kista.application.service.admin;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminManualTradeCorrectionCommand;
import com.kista.domain.model.admin.AdminTradeCorrectionResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminTradeCorrectionServiceTest {

    @Mock UserPort userPort;
    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminTradeCorrectionService service;

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();

    @Test
    void correctManualFills_liquidatingSell_savesFilledOrdersAndPausesStrategy() {
        User user = DomainFixtures.activeUserWithTelegram(USER_ID);
        Account account = new Account(ACCOUNT_ID, USER_ID, "KIS", "12345678", "app", "secret", null, Account.Broker.KIS, null);
        Strategy strategy = new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE,
                Strategy.Ticker.SOXL, Strategy.CycleSeedType.MAX);
        StrategyCycle cycle = new StrategyCycle(CYCLE_ID, STRATEGY_ID, VERSION_ID, new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID, new BigDecimal("6665.31"),
                new BigDecimal("266.65"), new BigDecimal("223.41"), 2, Instant.now(), null);
        AdminManualTradeCorrectionCommand command = new AdminManualTradeCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID,
                List.of(new AdminManualTradeCorrectionCommand.Fill(
                        LocalDate.of(2026, 7, 1), Order.OrderDirection.SELL, 2,
                        new BigDecimal("267.37"), "MANUAL-1", "manual correction")));

        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy);
        when(strategyPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(cycle));
        when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));

        AdminTradeCorrectionResult result = service.correctManualFills(ADMIN_ID, command);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.finalHoldings()).isZero();
        assertThat(result.strategyStatus()).isEqualTo(Strategy.Status.PAUSED);
        verify(orderPort).saveAll(any());
        verify(strategyCyclePort).markEnded(CYCLE_ID, new BigDecimal("7200.05"), LocalDate.of(2026, 7, 1));
        verify(strategyPort).save(argThat(s -> s.id().equals(STRATEGY_ID) && s.status() == Strategy.Status.PAUSED));

        ArgumentCaptor<CyclePosition> captor = ArgumentCaptor.forClass(CyclePosition.class);
        verify(cyclePositionPort).save(captor.capture());
        assertThat(captor.getValue().holdings()).isZero();
        assertThat(captor.getValue().usdDeposit()).isEqualByComparingTo("7200.05");
    }

    @Test
    void correctManualFills_sellQuantityGreaterThanHoldings_throws() {
        User user = DomainFixtures.activeUserWithTelegram(USER_ID);
        Account account = new Account(ACCOUNT_ID, USER_ID, "KIS", "12345678", "app", "secret", null, Account.Broker.KIS, null);
        Strategy strategy = new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE,
                Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(CYCLE_ID, STRATEGY_ID, VERSION_ID, new BigDecimal("6989.00"),
                null, LocalDate.of(2026, 6, 21), null, Instant.now(), null);
        CyclePosition latest = new CyclePosition(UUID.randomUUID(), CYCLE_ID, new BigDecimal("6665.31"),
                new BigDecimal("266.65"), new BigDecimal("223.41"), 2, Instant.now(), null);
        AdminManualTradeCorrectionCommand command = new AdminManualTradeCorrectionCommand(
                USER_ID, ACCOUNT_ID, STRATEGY_ID,
                List.of(new AdminManualTradeCorrectionCommand.Fill(
                        LocalDate.of(2026, 7, 1), Order.OrderDirection.SELL, 3,
                        new BigDecimal("267.37"), null, null)));

        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(user);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(cycle));
        when(cyclePositionPort.findLatestOne(CYCLE_ID)).thenReturn(Optional.of(latest));

        assertThatThrownBy(() -> service.correctManualFills(ADMIN_ID, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SELL quantity");

        verify(orderPort, never()).saveAll(any());
        verify(cyclePositionPort, never()).save(any());
    }
}
