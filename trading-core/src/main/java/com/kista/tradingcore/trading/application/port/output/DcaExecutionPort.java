package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.trading.domain.model.DcaExecution;

import com.kista.shared.kernel.DcaSkipReason;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DcaExecutionPort {

    // (strategyId, ruleKey, tradeDate) 최초 1회만 삽입 — 삽입했으면 true, UNIQUE 충돌이면 false
    boolean insertIfAbsent(DcaExecution execution);

    // 접수 거절된 주문의 ORDERED 이력을 SKIPPED로 전이 — status=ORDERED이고 order_id가 같은 행만, 바꿨으면 true
    boolean markOrderedSkipped(UUID strategyId, String ruleKey, LocalDate tradeDate, UUID orderId, DcaSkipReason reason, String detail);

    // 규칙·거래일 단건 조회
    Optional<DcaExecution> find(UUID strategyId, String ruleKey, LocalDate tradeDate);

    // 전략×규칙별 최신 회차 1건 (배치 조회) — strategyId → ruleKey → 최신 회차
    Map<UUID, Map<String, DcaExecution>> findLatestByStrategyIds(Collection<UUID> strategyIds);

    // 회차 이력 페이지 (trade_date DESC)
    List<DcaExecution> findPage(UUID strategyId, int page, int size);

    // 회차 이력 총 건수
    long count(UUID strategyId);
}
