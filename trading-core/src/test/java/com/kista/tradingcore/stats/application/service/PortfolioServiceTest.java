package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.stats.domain.model.PortfolioOrder;
import com.kista.tradingcore.stats.domain.model.PortfolioPosition;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.tradingcore.trading.domain.model.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock OrderPort orderPort;
    @Mock AccountPort accountPort;

    @InjectMocks PortfolioService sut;

    static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    static final LocalDate TO = LocalDate.of(2026, 1, 31);
    final UUID kisAccountId = UUID.randomUUID();
    final UUID binanceAccountId = UUID.randomUUID();

    private Account account(UUID id, Broker broker) {
        Account a = mock(Account.class);
        when(a.id()).thenReturn(id);
        when(a.broker()).thenReturn(broker);
        return a;
    }

    private void stubAccounts() {
        Account kis = account(kisAccountId, Broker.KIS);
        Account binance = account(binanceAccountId, Broker.BINANCE);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(kis, binance));
    }

    @Test
    void getCurrent는_계좌_통화를_붙인다() {
        stubAccounts();
        CyclePositionHistoryEntry kis = entry(kisAccountId, StrategyTicker.SOXL, StrategyType.INFINITE);
        CyclePositionHistoryEntry coin = entry(binanceAccountId, StrategyTicker.ETH, StrategyType.DCA);
        when(cyclePositionPort.findLatestPerStrategyByUser(USER_ID)).thenReturn(List.of(kis, coin));

        assertThat(sut.getCurrent(USER_ID)).containsExactly(
                new PortfolioPosition(kis, Currency.USD), new PortfolioPosition(coin, Currency.USDT));
    }

    @Test
    void getCurrent는_포지션_계좌가_없으면_IllegalStateException() {
        stubAccounts();
        when(cyclePositionPort.findLatestPerStrategyByUser(USER_ID))
                .thenReturn(List.of(entry(null, StrategyTicker.SOXL, StrategyType.INFINITE)));

        assertThatThrownBy(() -> sut.getCurrent(USER_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage("포지션 계좌를 찾을 수 없습니다.");
    }

    @Test
    void getHistory는_계좌_통화를_붙인다() {
        stubAccounts();
        Order kis = order(kisAccountId, StrategyTicker.TQQQ);
        Order coin = order(binanceAccountId, StrategyTicker.ETH);
        when(orderPort.findByUser(USER_ID, FROM, TO)).thenReturn(List.of(kis, coin));

        assertThat(sut.getHistory(USER_ID, FROM, TO)).containsExactly(
                new PortfolioOrder(kis, Currency.USD), new PortfolioOrder(coin, Currency.USDT));
    }

    @Test
    void 계좌_맵에_없는_주문은_IllegalStateException() {
        stubAccounts();
        when(orderPort.findByUser(USER_ID, FROM, TO)).thenReturn(List.of(order(UUID.randomUUID(), StrategyTicker.TQQQ)));

        assertThatThrownBy(() -> sut.getHistory(USER_ID, FROM, TO))
                .isInstanceOf(IllegalStateException.class).hasMessage("주문 계좌를 찾을 수 없습니다.");
    }

    private CyclePositionHistoryEntry entry(UUID accountId, StrategyTicker ticker, StrategyType type) {
        return new CyclePositionHistoryEntry(
                UUID.randomUUID(), accountId, ticker, type,
                new BigDecimal("1000.00"), new BigDecimal("26.00"),
                new BigDecimal("25.00"), BigDecimal.valueOf(30), Instant.now());
    }

    private Order order(UUID accountId, StrategyTicker ticker) {
        return new Order(UUID.randomUUID(), accountId, UUID.randomUUID(), LocalDate.of(2026, 1, 5), ticker,
                OrderType.LOC, null, OrderDirection.BUY, "leg", BigDecimal.valueOf(3), new BigDecimal("100.00"),
                null, null, null, null);
    }
}
