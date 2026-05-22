package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.util.List;

public record StrategyTypeMeta(
        String code,                   // enum name() 값
        String label,                  // 한국어 표시 이름
        String description,            // 전략 설명
        List<String> availableTickers, // 사용 가능한 티커 name() 목록 (정렬됨)
        String defaultTicker,          // 기본 선택 티커 name()
        BigDecimal defaultMultiple     // 기본 배수
) {
    public static StrategyTypeMeta from(Strategy.StrategyType t) {
        return new StrategyTypeMeta(
                t.name(), t.getLabel(), t.getDescription(),
                t.getAvailableTickers().stream()
                        .map(Enum::name).sorted().toList(),
                t.getDefaultTicker().name(),
                t.getDefaultMultiple()
        );
    }
}
