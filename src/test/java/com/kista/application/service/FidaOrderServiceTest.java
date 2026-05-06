package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FidaOrderServiceTest {

    @Mock KisOrderPort kisOrderPort;

    @InjectMocks
    FidaOrderService sut;

    @Test
    void execute_places_limit_order() {
        when(kisOrderPort.place(any())).thenAnswer(inv -> inv.getArgument(0));
        FidaOrderRequest req = new FidaOrderRequest(
                "SOXL", Order.OrderDirection.BUY, 5, new BigDecimal("25.50"));

        sut.execute(req);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(kisOrderPort).place(captor.capture());
        Order placed = captor.getValue();
        assertThat(placed.orderType()).isEqualTo(Order.OrderType.LIMIT);
        assertThat(placed.symbol()).isEqualTo("SOXL");
        assertThat(placed.qty()).isEqualTo(5);
        assertThat(placed.price()).isEqualByComparingTo("25.50");
    }
}
