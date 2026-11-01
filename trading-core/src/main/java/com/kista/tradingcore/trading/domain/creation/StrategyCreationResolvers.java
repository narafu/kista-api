package com.kista.tradingcore.trading.domain.creation;


import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.kista.shared.kernel.StrategyType;

// StrategyCreationResolver 구현체들을 type() 키로 묶어 조회 — 서비스 레이어의 if/else 타입 분기를 대체
public class StrategyCreationResolvers {

    private final Map<StrategyType, StrategyCreationResolver> byType;

    public StrategyCreationResolvers(List<StrategyCreationResolver> resolvers) {
        this.byType = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(StrategyCreationResolver::type, Function.identity()));
    }

    public StrategyCreationResolver of(StrategyType type) {
        StrategyCreationResolver resolver = byType.get(type);
        if (resolver == null) throw new IllegalStateException("전략 유형(%s)의 생성 리졸버가 등록되어 있지 않습니다.".formatted(type));
        return resolver;
    }
}
