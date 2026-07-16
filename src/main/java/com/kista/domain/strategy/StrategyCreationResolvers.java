package com.kista.domain.strategy;

import com.kista.domain.model.strategy.Strategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// StrategyCreationResolver 구현체들을 type() 키로 묶어 조회 — 서비스 레이어의 if/else 타입 분기를 대체
@Component
public class StrategyCreationResolvers {

    private final Map<Strategy.Type, StrategyCreationResolver> byType;

    public StrategyCreationResolvers(List<StrategyCreationResolver> resolvers) {
        this.byType = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(StrategyCreationResolver::type, Function.identity()));
    }

    public StrategyCreationResolver of(Strategy.Type type) {
        StrategyCreationResolver resolver = byType.get(type);
        if (resolver == null) throw new IllegalStateException("등록되지 않은 전략 생성 리졸버: " + type);
        return resolver;
    }
}
