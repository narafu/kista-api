package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;

public interface TosOrderPort {
    Order place(Order order, Account account);
    // Toss 주문 취소 접수. 실패 시 RuntimeException 전파.
    void cancel(Order order, Account account);
}
