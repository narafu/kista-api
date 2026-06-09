package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;

public record TickerMeta(
        String code,                   // enum name() 값
        String description,            // 종목 설명
        BigDecimal targetProfitRate    // 익절 목표 수익률
) {
    public static TickerMeta from(Strategy.Ticker t) {
        return new TickerMeta(
                t.name(), t.getDescription(), t.getTargetProfitRate()
        );
    }
}
