package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.BrokerSellableQuantityPort;
import com.kista.domain.port.out.KisSellableQuantityPort;
import com.kista.domain.port.out.TossSellableQuantityPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// account.broker() 기반 판매 가능 수량 조회 라우팅 — KisSellableQuantityPort/TossSellableQuantityPort 단일 진입점
@Component
@RequiredArgsConstructor
public class BrokerSellableQuantityRouter implements BrokerSellableQuantityPort {

    private final KisSellableQuantityPort kisSellableQuantityPort;
    private final TossSellableQuantityPort tossSellableQuantityPort;

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return switch (account.broker()) {
            case KIS -> kisSellableQuantityPort.getSellableQuantity(ticker, account);
            case TOSS -> tossSellableQuantityPort.getSellableQuantity(ticker, account);
        };
    }
}
