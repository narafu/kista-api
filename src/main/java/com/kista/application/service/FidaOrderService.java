package com.kista.application.service;

import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FidaOrderService implements ExecuteFidaOrderUseCase {

    @Override
    public void execute(FidaOrderRequest request) {
        /* todo
        * PrivacyTradeMasterEntity save
        *   -> request.tradeDate
        *   -> request.ticker
        *   -> request.currentCycleStart
        *   -> request.currentCycleRealizedPnl
        *   -> request.avgPrice
        *   -> request.holdings
        * PrivacyTradeDetailEntity save
        *   -> request.orders
        * */
    }
}
