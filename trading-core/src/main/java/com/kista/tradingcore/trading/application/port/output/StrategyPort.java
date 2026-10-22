package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategySummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;

public interface StrategyPort {
    List<Strategy> findByAccountId(UUID accountId);
    Optional<Strategy> findById(UUID id);

    // 없으면 NoSuchElementException
    default Strategy findByIdOrThrow(UUID strategyId) {
        return findById(strategyId).orElseThrow(
                () -> new NoSuchElementException("전략을 찾을 수 없습니다."));
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

    // strategyId 집합 → 전략 배치 조회 (CyclePositionPersistenceAdapter가 이력 entry에 ticker·type을 싣는다 — StrategyEntity 직접 접근 불가라 포트 경유)
    Map<UUID, Strategy> findAllByIds(Collection<UUID> strategyIds);

    // 같은 계좌·종목의 전략 — 등록 중복 규칙(DCA는 방향별 공존) 판정용
    List<Strategy> findByAccountIdAndTicker(UUID accountId, StrategyTicker ticker);

    // 시스템 자동 일시중지(사이클 재등록 실패 등) — 사용자 승인 흐름을 거치지 않는 강제 전이
    default void pause(UUID strategyId) {
        save(findByIdOrThrow(strategyId).withStatus(StrategyStatus.PAUSED));
    }
}
