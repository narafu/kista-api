package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class FidaOrderService implements ExecuteFidaOrderUseCase {

    private final KisOrderPort kisOrderPort;

    @Override
    public void execute(FidaOrderRequest request) {
        Order order = new Order(
                LocalDate.now(),
                request.symbol(),
                Order.OrderType.LIMIT,
                request.direction(),
                request.qty(),
                request.price(),
                Order.OrderStatus.PLACED,
                null);
        kisOrderPort.place(order);
    }
}
