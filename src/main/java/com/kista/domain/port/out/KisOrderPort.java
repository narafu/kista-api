package com.kista.domain.port.out;

import com.kista.domain.model.Order;

public interface KisOrderPort {
    Order place(String token, Order order);
}
