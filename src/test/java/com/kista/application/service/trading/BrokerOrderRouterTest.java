package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisOrderPort;
import com.kista.domain.port.out.TosOrderPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrokerOrderRouterTest {

    @Mock KisOrderPort kisOrderPort;
    @Mock TosOrderPort tosOrderPort;
    BrokerOrderRouter router;

    @BeforeEach
    void setUp() { router = new BrokerOrderRouter(kisOrderPort, tosOrderPort); }

    @Test
    @DisplayName("KIS 계좌 → kisOrderPort.place()")
    void place_kisAccount_routesToKis() {
        Account account = accountWith(Account.Broker.KIS);
        Order order = sampleOrder();
        when(kisOrderPort.place(order, account)).thenReturn(order);

        router.place(order, account);

        verify(kisOrderPort).place(order, account);
        verifyNoInteractions(tosOrderPort);
    }

    @Test
    @DisplayName("TOSS 계좌 → tosOrderPort.place()")
    void place_tossAccount_routesToToss() {
        Account account = accountWith(Account.Broker.TOSS);
        Order order = sampleOrder();
        when(tosOrderPort.place(order, account)).thenReturn(order);

        router.place(order, account);

        verify(tosOrderPort).place(order, account);
        verifyNoInteractions(kisOrderPort);
    }

    @Test
    @DisplayName("KIS 계좌 cancel → kisOrderPort.cancel()")
    void cancel_kisAccount_routesToKis() {
        Account account = accountWith(Account.Broker.KIS);
        Order order = sampleOrder();

        router.cancel(order, account);

        verify(kisOrderPort).cancel(order, account);
        verifyNoInteractions(tosOrderPort);
    }

    @Test
    @DisplayName("TOSS 계좌 cancel → tosOrderPort.cancel()")
    void cancel_tossAccount_routesToToss() {
        Account account = accountWith(Account.Broker.TOSS);
        Order order = sampleOrder();

        router.cancel(order, account);

        verify(tosOrderPort).cancel(order, account);
        verifyNoInteractions(kisOrderPort);
    }

    private Account accountWith(Account.Broker broker) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), "test",
                "acctno", "key", "secret", "01", broker);
    }

    private Order sampleOrder() {
        return new Order(UUID.randomUUID(), null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, BigDecimal.TEN,
                Order.OrderStatus.PLANNED, null, null, null);
    }
}
