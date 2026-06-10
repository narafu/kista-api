package com.kista.domain.port.out;

import com.kista.domain.model.strategy.Strategy;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface StrategyPort {
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
    void deleteByAccountId(UUID accountId); // 계좌 삭제 시 전략 일괄 소프트 삭제
    void deleteByUserId(UUID userId);       // 사용자 탈퇴 시 전략 일괄 소프트 삭제

    // 같은 계좌에 같은 종목 중복 방지 (체결 귀속을 위해 계좌 내 종목 유니크)
    boolean existsByAccountIdAndTicker(UUID accountId, Strategy.Ticker ticker);

    // ACTIVE 전략 ticker 우선, 없으면 첫 번째 전략 ticker 반환 — 없으면 empty
    default Optional<Strategy.Ticker> findActiveTicker(UUID accountId) {
        List<Strategy> strategies = findByAccountId(accountId);
        return strategies.stream()
                .filter(s -> s.status() == Strategy.Status.ACTIVE)
                .findFirst()
                .or(() -> strategies.stream().findFirst())
                .map(Strategy::ticker);
    }
}
