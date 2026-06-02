package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;

public interface KisOrderPort {
    Order place(Order order, Account account);
    // KIS 취소 주문 접수 (TTTT1004U). 실패 시 RuntimeException 전파.
    void cancel(Order order, Account account);
}
