package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCyclePosition;
import com.kista.domain.port.out.TradingCyclePositionPort;
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
class TradingCyclePositionPersistenceAdapter implements TradingCyclePositionPort {

    private final TradingCyclePositionJpaRepository jpaRepository;
    private final TradingCycleJpaRepository cycleJpaRepository; // ticker 조회용 (같은 패키지)

    @Override
    public TradingCyclePosition save(TradingCyclePosition position) {
        TradingCyclePositionEntity entity = toEntity(position);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<TradingCyclePosition> findRecentByCycleId(UUID cycleId, int limit) {
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
        List<TradingCyclePositionEntity> entities =
                jpaRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapFromEntities(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findBetween(LocalDate from, LocalDate to) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant fromInstant = from.atStartOfDay(kst).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(kst).toInstant(); // to 당일 포함
        List<TradingCyclePositionEntity> entities = jpaRepository.findBetweenDates(fromInstant, toInstant);
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapFromEntities(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from,
                                                                     Instant cursor, int limit) {
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapByAccountId(accountId);
        return jpaRepository.findByAccountIdWithCursor(accountId, from, cursor, PageRequest.of(0, limit))
                .stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findByCycleIdWithCursor(UUID cycleId, Instant from,
                                                                   Instant cursor, int limit) {
        TradingCycle.Ticker ticker = cycleJpaRepository.findById(cycleId)
                .map(TradingCycleEntity::getTicker).orElse(null);
        Map<UUID, TradingCycle.Ticker> tickerMap = ticker != null ? Map.of(cycleId, ticker) : Map.of();
        return jpaRepository.findByCycleIdWithCursor(cycleId, from, cursor, PageRequest.of(0, limit))
                .stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    // 계좌 ID로 사이클 목록을 조회해 cycleId → ticker 맵 구성
    private Map<UUID, TradingCycle.Ticker> buildTickerMapByAccountId(UUID accountId) {
        return cycleJpaRepository.findAllByAccountId(accountId).stream()
                .collect(Collectors.toMap(TradingCycleEntity::getId, TradingCycleEntity::getTicker));
    }

    // 이력 엔티티 목록의 cycleId 집합으로 ticker 맵 구성 (전역 조회용)
    private Map<UUID, TradingCycle.Ticker> buildTickerMapFromEntities(List<TradingCyclePositionEntity> entities) {
        Set<UUID> cycleIds = entities.stream()
                .map(TradingCyclePositionEntity::getTradingCycleId)
                .collect(Collectors.toSet());
        if (cycleIds.isEmpty()) return Map.of();
        return cycleJpaRepository.findAllById(cycleIds).stream()
                .collect(Collectors.toMap(TradingCycleEntity::getId, TradingCycleEntity::getTicker));
    }

    private AccountCycleHistoryEntry toEntry(TradingCyclePositionEntity e,
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

    private TradingCyclePosition toDomain(TradingCyclePositionEntity e) {
        return new TradingCyclePosition(
                e.getId(), e.getTradingCycleId(),
                e.getUsdDeposit(), e.getClosingPrice(), e.getAvgPrice(),
                e.getHoldings(), e.getCreatedAt()
        );
    }

    private TradingCyclePositionEntity toEntity(TradingCyclePosition p) {
        TradingCyclePositionEntity e = new TradingCyclePositionEntity();
        e.setId(p.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setTradingCycleId(p.tradingCycleId());
        e.setUsdDeposit(p.usdDeposit());
        e.setClosingPrice(p.closingPrice());
        e.setAvgPrice(p.avgPrice());
        e.setHoldings(p.holdings());
        return e;
    }
}
