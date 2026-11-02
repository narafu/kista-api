package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.trading.domain.model.CyclePositionInfiniteDetail;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CyclePositionInfiniteDetailPort {

    List<CyclePositionInfiniteDetail> findLatestByCycleId(UUID cycleId, int limit);

    // 사이클별 최신 INFINITE 상세 배치 조회 — 목록 N+1 방지 (키=cycleId)
    Map<UUID, CyclePositionInfiniteDetail> findLatestByCycleIds(Collection<UUID> cycleIds);

    CyclePositionInfiniteDetail save(CyclePositionInfiniteDetail detail);
}
