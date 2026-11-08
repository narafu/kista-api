package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategySummary;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import com.kista.shared.kernel.StrategyCycleSeedType;
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

    // PAUSED·ARCHIVED 전략 중 tradeDate에 접수된 주문(외부번호 보유·수동 보정 제외)이 있는 전략 — 마감 리포트 전용(보관 직전 접수분 체결이 원장에 남게)
    List<Strategy> findNotActiveWithReportableOrders(LocalDate tradeDate);

    Strategy save(Strategy strategy);

    // PAUSED → ARCHIVED 조건부 확정 — 그 사이 재개 등으로 PAUSED가 아니면 false(덮어쓰지 않는다)
    boolean archiveIfPaused(UUID id);

    // 상태만 조건부 갱신 — 보관(ARCHIVED) 행은 건드리지 않는다. 오래된 스냅샷 save가 보관을 덮어쓰는 경합 방지. 갱신됐으면 true
    boolean updateStatusUnlessArchived(UUID id, StrategyStatus status);
    // 연속 사이클 정책만 갱신 — 오래된 스냅샷 save가 status 등 다른 컬럼을 덮어쓰지 않게 한다(보관 행은 건드리지 않는다). 갱신됐으면 true
    boolean updateCycleSeedTypeUnlessArchived(UUID id, StrategyCycleSeedType cycleSeedType);
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
        updateStatusUnlessArchived(strategyId, StrategyStatus.PAUSED); // 보관 전략은 되돌리지 않는다
    }
}
