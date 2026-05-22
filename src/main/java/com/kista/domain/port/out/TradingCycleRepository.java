package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.TradingCycle;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface TradingCycleRepository {
    List<TradingCycle> findByAccountId(UUID accountId);
    Optional<TradingCycle> findById(UUID id);

    // 없으면 NoSuchElementException
    default TradingCycle findByIdOrThrow(UUID cycleId) {
        return findById(cycleId).orElseThrow(
                () -> new NoSuchElementException("거래 사이클을 찾을 수 없습니다: " + cycleId));
    }

    // 사용자 ACTIVE + 사이클 ACTIVE 전체 조회 (스케줄러용)
    List<TradingCycle> findAllActive();

    TradingCycle save(TradingCycle cycle);
    void delete(UUID id);

    // 같은 계좌에 같은 type 중복 방지
    boolean existsByAccountIdAndType(UUID accountId, TradingCycle.Type type);
}
