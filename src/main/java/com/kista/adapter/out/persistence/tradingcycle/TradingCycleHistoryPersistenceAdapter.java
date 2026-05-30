package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class TradingCycleHistoryPersistenceAdapter implements TradingCycleHistoryPort {

    private final TradingCycleHistoryJpaRepository jpaRepository;
    private final TradingCycleJpaRepository cycleJpaRepository; // ticker 조회용 (같은 패키지)

    @Override
    public TradingCycleHistory save(TradingCycleHistory history) {
        TradingCycleHistoryEntity entity = toEntity(history);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit) {
        // limit 무시하고 top10 조회 — 단순 구현, 필요 시 @Query로 동적 limit 추가
        return jpaRepository.findTop10ByTradingCycleIdOrderByCreatedAtDesc(cycleId).stream()
                .limit(limit)
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to) {
        // 계좌의 사이클 목록으로 ticker 맵 구성 (계좌당 최대 1개)
        Map<UUID, TradingCycle.Ticker> tickerMap = cycleJpaRepository.findAllByAccountId(accountId).stream()
                .collect(Collectors.toMap(TradingCycleEntity::getId, TradingCycleEntity::getTicker));

        return jpaRepository.findByAccountIdAndDateRange(accountId, from, to).stream()
                .map(e -> new AccountCycleHistoryEntry(
                        e.getId(),
                        tickerMap.get(e.getTradingCycleId()),
                        e.getUsdDeposit(),
                        e.getAvgPrice(),
                        e.getHoldings(),
                        e.getCreatedAt()
                ))
                .toList();
    }

    private TradingCycleHistory toDomain(TradingCycleHistoryEntity e) {
        return new TradingCycleHistory(
                e.getId(), e.getTradingCycleId(),
                e.getUsdDeposit(), e.getAvgPrice(), e.getHoldings(),
                e.getCreatedAt()
        );
    }

    private TradingCycleHistoryEntity toEntity(TradingCycleHistory h) {
        TradingCycleHistoryEntity e = new TradingCycleHistoryEntity();
        e.setId(h.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setTradingCycleId(h.tradingCycleId());
        e.setUsdDeposit(h.usdDeposit());
        e.setAvgPrice(h.avgPrice());
        e.setHoldings(h.holdings());
        return e;
    }
}
