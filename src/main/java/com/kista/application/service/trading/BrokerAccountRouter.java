package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.TosAccountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// account.broker() 기반으로 KIS/Toss 예수금 조회 라우터
@Component
@RequiredArgsConstructor
class BrokerAccountRouter {

    private final KisAccountPort kisAccountPort;
    private final TosAccountPort tosAccountPort;

    // 계좌의 USD 주문가능금액(실잔고) 조회 — getBalance().usdDeposit() 추출
    BigDecimal getUsdDeposit(Account account, Ticker ticker) {
        return switch (account.broker()) {
            case KIS  -> kisAccountPort.getBalance(account, ticker).usdDeposit();
            case TOSS -> tosAccountPort.getBalance(account, ticker).usdDeposit();
        };
    }
}
