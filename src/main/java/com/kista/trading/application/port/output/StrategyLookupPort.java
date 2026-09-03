package com.kista.trading.application.port.output;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.domain.model.StrategyRef;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

// trading이 정의하고 strategy-config가 구현하는 읽기 전용 포트(own-type 역전) — trading↔strategy-config 순환 해소.
// 조회(Query)만 담당 — 쓰기는 StrategyPausePort로 분리(ISP, ApprovalPolicyPort/BrokerEnabledPort와 동일하게
// 단일 책임 narrow 포트 관례를 따른다).
public interface StrategyLookupPort {
    List<StrategyRef> findAllActive();
    List<StrategyRef> findByAccountId(UUID accountId);
    Optional<StrategyRef> findById(UUID id);

    default StrategyRef findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> new NoSuchElementException("전략을 찾을 수 없습니다: " + id));
    }

    StrategyTicker findTickerById(UUID id);
    Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> ids);
}
