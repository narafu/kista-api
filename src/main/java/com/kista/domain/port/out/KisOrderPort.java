package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.Order;

public interface KisOrderPort {
    Order place(Order order, Account account);
}
