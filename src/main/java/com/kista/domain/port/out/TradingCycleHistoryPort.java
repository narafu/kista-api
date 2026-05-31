package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TradingCycleHistoryPort {
    TradingCycleHistory save(TradingCycleHistory history);

    List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit);

    // 계좌 ID 기준 이력 조회 (ticker 포함, 날짜 범위 필터)
    List<AccountCycleHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to);

    // 전체 이력 중 가장 최근 N건 (대시보드·텔레그램 현황 조회)
    List<AccountCycleHistoryEntry> findRecentGlobal(int limit);

    // 날짜 범위 이력 전체 (차트용 시계열) — from 당일 00:00 KST ~ to 익일 00:00 KST
    List<AccountCycleHistoryEntry> findBetween(LocalDate from, LocalDate to);
}
