package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.StrategyRepository;
import com.kista.domain.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NextOrdersServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock StrategyRepository strategyRepository;
    @Mock KisAccountPort kisAccountPort;
    @Mock KisPricePort kisPricePort;
    @Mock TradingStrategy tradingStrategy;

    NextOrdersService service;

    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();
    static final UUID OTHER_USER_ID = UUID.randomUUID();
    static final UUID STRATEGY_ID = UUID.randomUUID();
    static final BigDecimal PRICE = new BigDecimal("22.00");

    // Account 10개 필드 생성자
    static final Account ACCOUNT = new Account(
            ACCOUNT_ID, USER_ID, "테스트계좌",
            "74420614", "key", "secret", "01",
            Account.Broker.KIS, Instant.now(), Instant.now()
    );

    // Strategy record
    static final Strategy STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.StrategyType.INFINITE,
            Strategy.StrategyStatus.ACTIVE, Ticker.SOXL, BigDecimal.ONE,
            Instant.now(), Instant.now()
    );

    static final AccountBalance NORMAL_BALANCE = new AccountBalance(
            10, new BigDecimal("20.00"), new BigDecimal("1000.00"));

    static final AccountBalance LOW_BALANCE = new AccountBalance(
            0, BigDecimal.ZERO, BigDecimal.ZERO);

    @BeforeEach
    void setUp() {
        // NextOrdersService(accountRepository, strategyRepository, kisAccountPort, kisPricePort, tradingStrategy)
        service = new NextOrdersService(accountRepository, strategyRepository, kisAccountPort, kisPricePort, tradingStrategy);
    }

    @Test
    void preview_returns_result_with_orders_from_strategy() {
        Order order = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, PRICE, Order.OrderStatus.PLACED, null);

        when(accountRepository.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);
        when(strategyRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(STRATEGY));
        // getBalance(account, ticker) - 2개 파라미터
        when(kisAccountPort.getBalance(ACCOUNT, Ticker.SOXL)).thenReturn(NORMAL_BALANCE);
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(order));

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT_ID, USER_ID);

        assertThat(result.orders()).hasSize(1);
        assertThat(result.orders().get(0).ticker()).isEqualTo(Ticker.SOXL);
        assertThat(result.position().ticker()).isEqualTo(Ticker.SOXL);
        assertThat(result.position().currentPrice()).isEqualByComparingTo(PRICE);
        assertThat(result.tradeDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void preview_throws_NoSuchElementException_when_account_missing() {
        when(accountRepository.findByIdOrThrow(ACCOUNT_ID))
                .thenThrow(new NoSuchElementException("계좌를 찾을 수 없습니다: " + ACCOUNT_ID));

        assertThatThrownBy(() -> service.preview(ACCOUNT_ID, USER_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(ACCOUNT_ID.toString());
    }

    @Test
    void preview_throws_SecurityException_when_not_owner() {
        when(accountRepository.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);

        assertThatThrownBy(() -> service.preview(ACCOUNT_ID, OTHER_USER_ID))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void preview_calls_buildOrders_even_when_balance_should_skip() {
        when(accountRepository.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);
        when(strategyRepository.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(STRATEGY));
        when(kisAccountPort.getBalance(ACCOUNT, Ticker.SOXL)).thenReturn(LOW_BALANCE);
        when(kisPricePort.getPrice(Ticker.SOXL, ACCOUNT)).thenReturn(PRICE);
        when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of());

        GetNextOrdersUseCase.Result result = service.preview(ACCOUNT_ID, USER_ID);

        assertThat(result.orders()).isEmpty();
        verify(tradingStrategy).buildOrders(any(InfinitePosition.class), any(LocalDate.class));
    }
}
