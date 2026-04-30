package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import com.kista.domain.port.out.KisTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FidaOrderServiceTest {

    @Mock KisTokenPort kisTokenPort;
    @Mock KisOrderPort kisOrderPort;

    @InjectMocks
    FidaOrderService sut;

    @Test
    void execute_places_limit_order_with_token() {
        when(kisTokenPort.getToken()).thenReturn("test-token");
        when(kisOrderPort.place(eq("test-token"), any())).thenAnswer(inv -> inv.getArgument(1));
        FidaOrderRequest req = new FidaOrderRequest(
                "SOXL", Order.OrderDirection.BUY, 5, new BigDecimal("25.50"));

        sut.execute(req);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(kisOrderPort).place(eq("test-token"), captor.capture());
        Order placed = captor.getValue();
        assertThat(placed.orderType()).isEqualTo(Order.OrderType.LIMIT);
        assertThat(placed.symbol()).isEqualTo("SOXL");
        assertThat(placed.qty()).isEqualTo(5);
        assertThat(placed.price()).isEqualByComparingTo("25.50");
    }
}
