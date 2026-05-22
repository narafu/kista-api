package com.kista.domain.port.out;

import com.kista.domain.model.strategy.Strategy;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface StrategyRepository {
    List<Strategy> findByAccountId(UUID accountId);
    Optional<Strategy> findById(UUID id);

    // 없으면 NoSuchElementException
    default Strategy findByIdOrThrow(UUID strategyId) {
        return findById(strategyId).orElseThrow(
                () -> new NoSuchElementException("전략을 찾을 수 없습니다: " + strategyId));
    }

    // 사용자 ACTIVE + 전략 ACTIVE 전체 조회 (스케줄러용)
    List<Strategy> findAllActive();

    Strategy save(Strategy strategy);
    void delete(UUID id);

    // 같은 계좌에 같은 type 중복 방지
    boolean existsByAccountIdAndType(UUID accountId, Strategy.StrategyType type);
}
