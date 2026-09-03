package com.kista.trading.application.service;

import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
import com.kista.trading.domain.strategy.InfiniteCreationResolver;
import com.kista.trading.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.trading.domain.strategy.InfiniteStrategy;
import com.kista.trading.domain.strategy.PrivacyCreationResolver;
import com.kista.trading.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.trading.domain.strategy.PrivacyStrategy;
import com.kista.trading.domain.strategy.ReverseInfiniteStrategy;
import com.kista.trading.domain.strategy.StrategyCreationResolver;
import com.kista.trading.domain.strategy.StrategyCreationResolvers;
import com.kista.trading.domain.strategy.VrCreationResolver;
import com.kista.trading.domain.strategy.VrCycleOrderStrategy;
import com.kista.trading.domain.strategy.VrStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// domain/strategy 전략 구현체를 Spring 빈으로 명시 배선 — 도메인 레이어를 Spring 비의존으로 유지하기 위해
// @Component 대신 이 팩토리가 인스턴스화한다(stats BacktestEngine이 순수 도메인 원칙으로 직접 new 하는 것과 동일 취지).
@Configuration
class CycleStrategyBeanConfig {

    // 무상태 순수 계산 클래스 — 의존성 없음
    @Bean
    InfiniteStrategy infiniteStrategy() { return new InfiniteStrategy(); }

    @Bean
    ReverseInfiniteStrategy reverseInfiniteStrategy() { return new ReverseInfiniteStrategy(); }

    @Bean
    PrivacyStrategy privacyStrategy() { return new PrivacyStrategy(); }

    @Bean
    VrStrategy vrStrategy() { return new VrStrategy(); }

    // 전략별 CycleOrderStrategy 구현 — leaf 계산 클래스 조합
    @Bean
    InfiniteCycleOrderStrategy infiniteCycleOrderStrategy(InfiniteStrategy infiniteStrategy,
                                                         ReverseInfiniteStrategy reverseInfiniteStrategy) {
        return new InfiniteCycleOrderStrategy(infiniteStrategy, reverseInfiniteStrategy);
    }

    @Bean
    PrivacyCycleOrderStrategy privacyCycleOrderStrategy(PrivacyStrategy privacyStrategy) {
        return new PrivacyCycleOrderStrategy(privacyStrategy);
    }

    @Bean
    VrCycleOrderStrategy vrCycleOrderStrategy(VrStrategy vrStrategy) {
        return new VrCycleOrderStrategy(vrStrategy);
    }

    // 전략 등록 시 런타임 설정 해석 리졸버 — 의존성 없음
    @Bean
    InfiniteCreationResolver infiniteCreationResolver() { return new InfiniteCreationResolver(); }

    @Bean
    PrivacyCreationResolver privacyCreationResolver() { return new PrivacyCreationResolver(); }

    @Bean
    VrCreationResolver vrCreationResolver() { return new VrCreationResolver(); }

    // 타입 → 구현 라우터 — Spring이 위 @Bean 컬렉션을 주입
    @Bean
    CycleOrderStrategies cycleOrderStrategies(List<CycleOrderStrategy> strategies) {
        return new CycleOrderStrategies(strategies);
    }

    @Bean
    StrategyCreationResolvers strategyCreationResolvers(List<StrategyCreationResolver> resolvers) {
        return new StrategyCreationResolvers(resolvers);
    }
}
