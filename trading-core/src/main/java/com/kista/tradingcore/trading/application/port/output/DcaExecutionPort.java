package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.trading.domain.model.DcaExecution;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DcaExecutionPort {

    // (strategyId, tradeDate) 최초 1회만 삽입 — 삽입했으면 true, UNIQUE 충돌이면 false
    boolean insertIfAbsent(DcaExecution execution);

    // 거래일 단건 조회
    Optional<DcaExecution> find(UUID strategyId, LocalDate tradeDate);

    // 전략별 최신 회차 1건 (배치 조회) — 키는 strategyId
    Map<UUID, DcaExecution> findLatestByStrategyIds(Collection<UUID> strategyIds);

    // 회차 이력 페이지 (trade_date DESC)
    List<DcaExecution> findPage(UUID strategyId, int page, int size);

    // 회차 이력 총 건수
    long count(UUID strategyId);
}
