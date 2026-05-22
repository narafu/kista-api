package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.port.out.TradingCycleHistoryRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class TradingCycleHistoryPersistenceAdapter implements TradingCycleHistoryRepository {

    private final TradingCycleHistoryJpaRepository jpaRepository;

    @Override
    public TradingCycleHistory save(TradingCycleHistory history) {
        TradingCycleHistoryEntity entity = toEntity(history);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<TradingCycleHistory> findByCycleIdAndDate(UUID cycleId, LocalDate date) {
        return jpaRepository.findByTradingCycleIdAndTradeDate(cycleId, date).map(this::toDomain);
    }

    @Override
    public List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit) {
        // limit 무시하고 top10 조회 — 단순 구현, 필요 시 @Query로 동적 limit 추가
        return jpaRepository.findTop10ByTradingCycleIdOrderByTradeDateDesc(cycleId).stream()
                .limit(limit)
                .map(this::toDomain)
                .toList();
    }

    private TradingCycleHistory toDomain(TradingCycleHistoryEntity e) {
        return new TradingCycleHistory(
                e.getId(), e.getTradingCycleId(), e.getTradeDate(),
                e.getUsdDeposit(), e.getAvgPrice(), e.getHoldings(),
                e.getCreatedAt()
        );
    }

    private TradingCycleHistoryEntity toEntity(TradingCycleHistory h) {
        TradingCycleHistoryEntity e = new TradingCycleHistoryEntity();
        e.setId(h.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setTradingCycleId(h.tradingCycleId());
        e.setTradeDate(h.tradeDate());
        e.setUsdDeposit(h.usdDeposit());
        e.setAvgPrice(h.avgPrice());
        e.setHoldings(h.holdings());
        return e;
    }
}
