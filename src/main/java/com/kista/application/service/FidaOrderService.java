package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import com.kista.domain.port.out.KisTokenPort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class FidaOrderService implements ExecuteFidaOrderUseCase {

    private final KisTokenPort kisTokenPort;
    private final KisOrderPort kisOrderPort;

    public FidaOrderService(KisTokenPort kisTokenPort, KisOrderPort kisOrderPort) {
        this.kisTokenPort = kisTokenPort;
        this.kisOrderPort = kisOrderPort;
    }

    @Override
    public void execute(FidaOrderRequest request) {
        String token = kisTokenPort.getToken();
        Order order = new Order(
                LocalDate.now(),
                request.symbol(),
                Order.OrderType.LIMIT,
                request.direction(),
                request.qty(),
                request.price(),
                Order.OrderStatus.PLACED,
                null);
        kisOrderPort.place(token, order);
    }
}
