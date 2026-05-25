package com.kista.adapter.in.web.dto;

import com.kista.domain.model.tradingcycle.TradingCycle;

import java.util.List;

public record StrategyTypeMeta(
        String code,                   // enum name() 값
        String description,            // 전략 설명
        List<String> availableTickers  // 사용 가능한 티커 name() 목록 (정렬됨)
) {
    public static StrategyTypeMeta from(TradingCycle.Type t) {
        return new StrategyTypeMeta(
                t.name(), t.getDescription(),
                t.availableTickers().stream()
                        .map(Enum::name).sorted().toList()
        );
    }
}
