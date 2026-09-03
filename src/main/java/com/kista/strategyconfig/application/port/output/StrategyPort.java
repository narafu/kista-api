package com.kista.strategyconfig.application.port.output;

import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.strategyconfig.domain.model.StrategySummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.StrategyTicker;

public interface StrategyPort {
    List<Strategy> findByAccountId(UUID accountId);
    Optional<Strategy> findById(UUID id);

    // 없으면 NoSuchElementException
    default Strategy findByIdOrThrow(UUID strategyId) {
        return findById(strategyId).orElseThrow(
                () -> new NoSuchElementException("전략을 찾을 수 없습니다: " + strategyId));
    }

    // 사용자 ACTIVE + 전략 ACTIVE 전체 조회 (스케쥴러용)
    List<Strategy> findAllActive();

    Strategy save(Strategy strategy);
    void delete(UUID id);
    void deleteByAccountId(UUID accountId); // 계좌 삭제 시 전략 일괄 소프트 삭제
    void deleteByUserId(UUID userId);       // 사용자 탈퇴 시 전략 일괄 소프트 삭제

    // 여러 계좌 ID → 전략 목록 배치 조회 (관리자 계좌 목록용)
    Map<UUID, List<Strategy>> findByAccountIds(Collection<UUID> accountIds);

    // strategy_cycle.id → strategyId + strategy.type 배치 조회 (관리자 거래내역용 — admin이 자체 조립)
    Map<UUID, StrategySummary> findSummariesByCycleIds(Collection<UUID> cycleIds);

    // strategyId 집합 → ticker 배치 조회 (com.kista.trading의 CyclePositionPersistenceAdapter가 StrategyEntity 직접 접근 불가라 포트 경유)
    Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> strategyIds);

    // 같은 계좌에 같은 종목 중복 방지 (체결 귀속을 위해 계좌 내 종목 유니크)
    boolean existsByAccountIdAndTicker(UUID accountId, StrategyTicker ticker);
}
