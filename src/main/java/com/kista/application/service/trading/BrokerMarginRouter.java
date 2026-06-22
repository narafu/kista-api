package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.BrokerMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// USD 매수가능금액을 브로커 무관하게 단일 인터페이스로 제공
@Component
@RequiredArgsConstructor
public class BrokerMarginRouter {

    private final BrokerMarginPort brokerMarginPort;

    public BigDecimal getUsdBuyableAmount(Account account) {
        return brokerMarginPort.getUsdBuyableAmount(account);
    }
}
