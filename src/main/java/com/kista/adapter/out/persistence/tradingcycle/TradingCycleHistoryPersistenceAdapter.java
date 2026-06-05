package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public List<AccountCycleHistoryEntry> findByCycleIdAndDateRange(UUID cycleId, Instant from, Instant to) {
        TradingCycle.Ticker ticker = cycleJpaRepository.findById(cycleId)
                .map(TradingCycleEntity::getTicker).orElse(null);
        Map<UUID, TradingCycle.Ticker> tickerMap = ticker != null ? Map.of(cycleId, ticker) : Map.of();
        return jpaRepository.findByCycleIdAndDateRange(cycleId, from, to).stream()
                .map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to) {
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapByAccountId(accountId);
        return jpaRepository.findByAccountIdAndDateRange(accountId, from, to).stream()
                .map(e -> toEntry(e, tickerMap))
                .toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findRecentGlobal(int limit) {
        List<TradingCycleHistoryEntity> entities =
                jpaRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapFromEntities(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findBetween(LocalDate from, LocalDate to) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant fromInstant = from.atStartOfDay(kst).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(kst).toInstant(); // to 당일 포함
        List<TradingCycleHistoryEntity> entities = jpaRepository.findBetweenDates(fromInstant, toInstant);
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapFromEntities(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    // 계좌 ID로 사이클 목록을 조회해 cycleId → ticker 맵 구성
    private Map<UUID, TradingCycle.Ticker> buildTickerMapByAccountId(UUID accountId) {
        return cycleJpaRepository.findAllByAccountId(accountId).stream()
                .collect(Collectors.toMap(TradingCycleEntity::getId, TradingCycleEntity::getTicker));
    }

    // 이력 엔티티 목록의 cycleId 집합으로 ticker 맵 구성 (전역 조회용)
    private Map<UUID, TradingCycle.Ticker> buildTickerMapFromEntities(List<TradingCycleHistoryEntity> entities) {
        Set<UUID> cycleIds = entities.stream()
                .map(TradingCycleHistoryEntity::getTradingCycleId)
                .collect(Collectors.toSet());
        if (cycleIds.isEmpty()) return Map.of();
        return cycleJpaRepository.findAllById(cycleIds).stream()
                .collect(Collectors.toMap(TradingCycleEntity::getId, TradingCycleEntity::getTicker));
    }

    private AccountCycleHistoryEntry toEntry(TradingCycleHistoryEntity e,
                                             Map<UUID, TradingCycle.Ticker> tickerMap) {
        return new AccountCycleHistoryEntry(
                e.getId(),
                tickerMap.get(e.getTradingCycleId()),
                e.getUsdDeposit(),
                e.getClosingPrice(),
                e.getAvgPrice(),
                e.getHoldings(),
                e.getCreatedAt()
        );
    }

    private TradingCycleHistory toDomain(TradingCycleHistoryEntity e) {
        return new TradingCycleHistory(
                e.getId(), e.getTradingCycleId(),
                e.getUsdDeposit(), e.getClosingPrice(), e.getAvgPrice(),
                e.getHoldings(), e.getCreatedAt()
        );
    }

    private TradingCycleHistoryEntity toEntity(TradingCycleHistory h) {
        TradingCycleHistoryEntity e = new TradingCycleHistoryEntity();
        e.setId(h.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setTradingCycleId(h.tradingCycleId());
        e.setUsdDeposit(h.usdDeposit());
        e.setClosingPrice(h.closingPrice());
        e.setAvgPrice(h.avgPrice());
        e.setHoldings(h.holdings());
        return e;
    }
}
