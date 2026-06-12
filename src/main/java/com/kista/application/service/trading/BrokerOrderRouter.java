package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.out.KisOrderPort;
import com.kista.domain.port.out.TosOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// package-private — account.broker() 기반으로 KIS/Toss 주문 포트 선택
@Component
@RequiredArgsConstructor
class BrokerOrderRouter {

    private final KisOrderPort kisOrderPort;
    private final TosOrderPort tosOrderPort;

    Order place(Order order, Account account) {
        return switch (account.broker()) {
            case KIS -> kisOrderPort.place(order, account);
            case TOSS -> tosOrderPort.place(order, account);
        };
    }

    void cancel(Order order, Account account) {
        switch (account.broker()) {
            case KIS -> kisOrderPort.cancel(order, account);
            case TOSS -> tosOrderPort.cancel(order, account);
        }
    }
}
