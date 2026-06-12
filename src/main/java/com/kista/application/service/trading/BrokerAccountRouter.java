package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.TosAccountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// package-private — account.broker() 기반으로 KIS/Toss 잔고 포트 선택
@Component
@RequiredArgsConstructor
class BrokerAccountRouter {

    private final KisAccountPort kisAccountPort;
    private final TosAccountPort tosAccountPort;

    AccountBalance getBalance(Account account, Ticker ticker) {
        return switch (account.broker()) {
            case KIS -> kisAccountPort.getBalance(account, ticker);
            case TOSS -> tosAccountPort.getBalance(account, ticker);
        };
    }
}
