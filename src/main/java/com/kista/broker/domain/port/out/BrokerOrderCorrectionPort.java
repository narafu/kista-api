package com.kista.broker.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;

public interface BrokerOrderCorrectionPort {
    void cancel(Order order, Account account);
    Order place(Order order, Account account);
}
