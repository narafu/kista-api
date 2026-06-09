package com.kista.domain.strategy;

import com.kista.domain.model.strategy.Strategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

// CycleOrderStrategy 구현체들을 cycleType() 키로 묶어 조회 — 서비스 레이어의 switch 분기를 대체
@Component
public class CycleOrderStrategies {

    private final Map<Strategy.Type, CycleOrderStrategy> byType;

    public CycleOrderStrategies(List<CycleOrderStrategy> strategies) {
        this.byType = strategies.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        CycleOrderStrategy::cycleType, Function.identity()));
    }

    public CycleOrderStrategy of(Strategy.Type type) {
        CycleOrderStrategy s = byType.get(type);
        if (s == null) throw new IllegalStateException("등록되지 않은 cycleType: " + type);
        return s;
    }

    public CycleOrderStrategy of(Strategy strategy) {
        return of(strategy.type());
    }
}
