package com.kista.tradingcore.trading.application.port.output;

import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CycleCapitalEventPort {

    CycleCapitalEvent save(CycleCapitalEvent event);

    // occurredAt 오름차순, 빈 입력이면 DB 조회 없이 빈 목록
    List<CycleCapitalEvent> findByCycleIds(Collection<UUID> cycleIds);

    boolean existsByCycleId(UUID cycleId);
}
