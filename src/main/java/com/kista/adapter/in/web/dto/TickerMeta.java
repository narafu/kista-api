package com.kista.adapter.in.web.dto;

import com.kista.domain.model.tradingcycle.TradingCycle;

import java.math.BigDecimal;

public record TickerMeta(
        String code,                   // enum name() 값
        String description,            // 종목 설명
        BigDecimal targetProfitRate    // 익절 목표 수익률
) {
    public static TickerMeta from(TradingCycle.Ticker t) {
        return new TickerMeta(
                t.name(), t.getDescription(), t.getTargetProfitRate()
        );
    }
}
