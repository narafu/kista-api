package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCyclePosition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TradingCyclePositionPort {
    TradingCyclePosition save(TradingCyclePosition position);

    List<TradingCyclePosition> findRecentByCycleId(UUID cycleId, int limit);

    // 계좌 ID 기준 이력 조회 (ticker 포함, 날짜 범위 필터)
    List<AccountCycleHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to);

    // 전략(사이클) ID 기준 이력 조회 (날짜 범위 필터)
    List<AccountCycleHistoryEntry> findByCycleIdAndDateRange(UUID cycleId, Instant from, Instant to);

    // 전체 이력 중 가장 최근 N건 (대시보드·텔레그램 현황 조회)
    List<AccountCycleHistoryEntry> findRecentGlobal(int limit);

    // 날짜 범위 이력 전체 (차트용 시계열) — from 당일 00:00 KST ~ to 익일 00:00 KST
    List<AccountCycleHistoryEntry> findBetween(LocalDate from, LocalDate to);

    // 커서 기반 페이지 조회 — limit건 반환 (hasMore 판단용으로 limit+1 전달 권장)
    List<AccountCycleHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from, Instant cursor, int limit);

    List<AccountCycleHistoryEntry> findByCycleIdWithCursor(UUID cycleId, Instant from, Instant cursor, int limit);
}
