package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.model.BatchContext;

import java.util.UUID;

// 계좌별 현재가 맵의 키 — 같은 종목이라도 계좌(증권사)마다 값이 다르므로 계좌 사이에 공유하지 않는다
record PriceKey(UUID accountId, StrategyTicker ticker) {

    // 배치 컨텍스트의 계좌·전략 종목으로 키를 만든다
    static PriceKey of(BatchContext ctx) {
        return new PriceKey(ctx.account().id(), ctx.strategy().ticker());
    }
}
