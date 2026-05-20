package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;

public interface KisOrderPort {
    Order place(Order order, Account account);
}
