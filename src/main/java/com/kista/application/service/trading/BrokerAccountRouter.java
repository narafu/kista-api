package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.TosAccountPort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// account.broker() 기반으로 KIS/Toss 잔고 조회 라우터
@Component
@RequiredArgsConstructor
class BrokerAccountRouter {

    private final KisAccountPort kisAccountPort;
    private final TosAccountPort tosAccountPort;
    private final BrokerAdapterRegistry registry;

    // live 잔고 조회 — holdings + usdDeposit. 주문 저장 직전 유효성 검사에서 호출
    AccountBalance getLiveBalance(Account account, Ticker ticker) {
        return switch (account.broker()) {
            case KIS  -> kisAccountPort.getBalance(account, ticker);
            case TOSS -> tosAccountPort.getBalance(account, ticker);
        };
    }

    // usdDeposit만 필요할 때 getLiveBalance() 위임
    BigDecimal getUsdDeposit(Account account, Ticker ticker) {
        return getLiveBalance(account, ticker).usdDeposit();
    }

    // 판매 가능 수량 조회 — KIS: CTRP6504R 체결기준현재잔고 / Toss: /api/v1/sellable-quantity
    int getSellableQuantity(Account account, Ticker ticker) {
        return registry.require(account, SellableQuantityPort.class)
                .getSellableQuantity(ticker, account)
                .quantity();
    }
}
