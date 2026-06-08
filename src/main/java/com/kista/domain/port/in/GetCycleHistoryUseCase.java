package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.CycleHistoryPage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// trading_cycle_history DB 커서 페이지 조회 (KIS API 미사용)
public interface GetCycleHistoryUseCase {

    // 계좌 기준 — cursor=null이면 to 기준 첫 페이지
    CycleHistoryPage getByAccount(UUID accountId, UUID requesterId,
                                   LocalDate from, LocalDate to,
                                   Instant cursor, int size);

    // 전략(사이클) 기준
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                    LocalDate from, LocalDate to,
                                    Instant cursor, int size);
}
