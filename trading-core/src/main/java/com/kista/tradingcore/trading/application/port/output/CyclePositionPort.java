package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CyclePositionPort {
    CyclePosition save(CyclePosition position);

    // 전략 기준 현재 포지션 조회 (strategy → strategy_cycle → cycle_position 조인)
    // 최신 cycle_position.createdAt 순 limit건 반환
    List<CyclePosition> findLatestByStrategyId(UUID strategyId, int limit);

    // 사이클 ID 기준 최신 N개 포지션 (리버스모드 별지점 계산용 — 최근 closing_price 추출)
    List<CyclePosition> findLatestByCycleId(UUID cycleId, int limit);

    // INFINITE 상세가 있는 최신 포지션 N건 — 리포트·등록 개장 행만, 수동 보정·로테이션 개장 행 제외(새 사이클은 보유 0 시작이라 리버스 전 창이 리포트 행으로 참)
    List<CyclePosition> findLatestWithInfiniteDetailByCycleId(UUID cycleId, int limit);

    // 사이클 ID 기준 최신 포지션 1건 — findLatestByCycleId(id, 1).stream().findFirst() 축약
    default Optional<CyclePosition> findLatestOne(UUID cycleId) {
        return findLatestByCycleId(cycleId, 1).stream().findFirst();
    }

    // 사이클 ID 기준 개장 포지션 1건 — 생성일이 가장 오래된 활성 포지션
    Optional<CyclePosition> findFirstOne(UUID cycleId);

    // 전략 ID 기준 최신 포지션 1건 — findLatestByStrategyId(id, 1).stream().findFirst() 축약
    default Optional<CyclePosition> findLatestOneByStrategyId(UUID strategyId) {
        return findLatestByStrategyId(strategyId, 1).stream().findFirst();
    }

    // 여러 사이클의 개장 포지션 배치 조회 (목록 조회 N+1 방지) — cycleId 기준 가장 오래된 1건씩
    Map<UUID, CyclePosition> findFirstByCycleIds(Collection<UUID> cycleIds);

    // 여러 사이클의 최신 포지션 배치 조회 (목록 조회 N+1 방지) — cycleId 기준 최신 1건씩
    Map<UUID, CyclePosition> findLatestByCycleIds(Collection<UUID> cycleIds);

    // 사용자 스코프 전략별 최신 포지션 1건 (관리자 /status — 본인 데이터만, 코인 계좌 포함)
    List<CyclePositionHistoryEntry> findLatestPerStrategyByUser(UUID userId);

    // 사용자 스코프 스냅샷 범위 조회 (통계 equity curve용) — created_at 오름차순
    List<CyclePosition> findByUserAndRange(UUID userId, Instant from, Instant to);

    // 사이클 ID 목록 스코프 범위 조회 (통계 equity curve type 필터용 — 이미 알고 있는 cycleIds로 DB 조회 자체를 좁힘) — created_at 오름차순
    List<CyclePosition> findByCycleIdsAndRange(Set<UUID> cycleIds, Instant from, Instant to);

    // 개별 전략 스코프 월별 수익률 계산용 — created_at 오름차순
    List<CyclePosition> findByStrategyAndRange(UUID strategyId, Instant from, Instant to);

    // 커서 기반 페이지 조회 — limit건 반환 (hasMore 판단용으로 limit+1 전달 권장)
    List<CyclePositionHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from, Instant cursor, int limit);

    List<CyclePositionHistoryEntry> findByStrategyIdWithCursor(UUID strategyId, Instant from, Instant cursor, int limit);

    // 특정 포지션 행의 잔고(보유·평단·예수금) in-place 갱신 — 투자금 추가용(행·detail 유지)
    void updateBalance(UUID cyclePositionId, AccountBalance balance);

    // 전략 삭제 시 포지션 일괄 소프트 삭제
    void deleteByStrategyId(UUID strategyId);
    void deleteByAccountId(UUID accountId);
    void deleteByUserId(UUID userId);
}
