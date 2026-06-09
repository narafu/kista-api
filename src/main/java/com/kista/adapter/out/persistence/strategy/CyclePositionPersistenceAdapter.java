package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.CyclePositionPort;
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
class CyclePositionPersistenceAdapter implements CyclePositionPort {

    private final CyclePositionJpaRepository positionRepo;
    private final StrategyCycleJpaRepository cycleRepo;     // strategy_cycle 조인: 현재 사이클 조회
    private final StrategyJpaRepository strategyRepo;        // ticker 조회: strategy_cycle → strategy

    @Override
    public CyclePosition save(CyclePosition position) {
        return toDomain(positionRepo.save(toEntity(position)));
    }

    @Override
    public List<CyclePosition> findLatestByStrategyId(UUID strategyId, int limit) {
        // 현재 사이클(latest strategy_cycle) 의 최신 포지션 N건 반환
        return cycleRepo.findTop1ByStrategyIdOrderByCreatedAtDesc(strategyId)
                .map(sc -> positionRepo.findTopNByStrategyCycleIdOrderByCreatedAtDesc(
                        sc.getId(), PageRequest.of(0, limit)))
                .orElse(List.of())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to) {
        List<CyclePositionEntity> entities = positionRepo.findByAccountIdAndDateRange(accountId, from, to);
        Map<UUID, Strategy.Ticker> tickerMap = buildTickerMapFromPositions(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findByStrategyIdAndDateRange(UUID strategyId, Instant from, Instant to) {
        // ticker는 strategy에서 한 번만 조회
        Strategy.Ticker ticker = strategyRepo.findById(strategyId)
                .map(StrategyEntity::getTicker).orElse(null);
        return positionRepo.findByStrategyIdAndDateRange(strategyId, from, to).stream()
                .map(e -> toEntry(e, ticker))
                .toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findRecentGlobal(int limit) {
        List<CyclePositionEntity> entities =
                positionRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
        Map<UUID, Strategy.Ticker> tickerMap = buildTickerMapFromPositions(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findBetween(LocalDate from, LocalDate to) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant fromInstant = from.atStartOfDay(kst).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(kst).toInstant(); // to 당일 포함
        List<CyclePositionEntity> entities = positionRepo.findBetweenDates(fromInstant, toInstant);
        Map<UUID, Strategy.Ticker> tickerMap = buildTickerMapFromPositions(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from,
                                                                      Instant cursor, int limit) {
        List<CyclePositionEntity> entities = positionRepo.findByAccountIdWithCursor(
                accountId, from, cursor, PageRequest.of(0, limit));
        Map<UUID, Strategy.Ticker> tickerMap = buildTickerMapFromPositions(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findByStrategyIdWithCursor(UUID strategyId, Instant from,
                                                                       Instant cursor, int limit) {
        Strategy.Ticker ticker = strategyRepo.findById(strategyId)
                .map(StrategyEntity::getTicker).orElse(null);
        return positionRepo.findByStrategyIdWithCursor(strategyId, from, cursor, PageRequest.of(0, limit))
                .stream().map(e -> toEntry(e, ticker)).toList();
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        positionRepo.softDeleteByStrategyId(strategyId, Instant.now());
    }

    @Override
    public void deleteByAccountId(UUID accountId) {
        positionRepo.softDeleteByAccountId(accountId, Instant.now());
    }

    @Override
    public void deleteByUserId(UUID userId) {
        positionRepo.softDeleteByUserId(userId, Instant.now());
    }

    // cycle_position 목록에서 strategy_cycle → strategy 경유 ticker 맵 구성
    private Map<UUID, Strategy.Ticker> buildTickerMapFromPositions(List<CyclePositionEntity> entities) {
        Set<UUID> cycleIds = entities.stream()
                .map(CyclePositionEntity::getStrategyCycleId)
                .collect(Collectors.toSet());
        if (cycleIds.isEmpty()) return Map.of();
        // strategy_cycle → strategy_id → ticker
        Set<UUID> strategyIds = cycleRepo.findAllById(cycleIds).stream()
                .map(StrategyCycleEntity::getStrategyId)
                .collect(Collectors.toSet());
        Map<UUID, Strategy.Ticker> strategyTickerMap = strategyRepo.findAllById(strategyIds).stream()
                .collect(Collectors.toMap(StrategyEntity::getId, StrategyEntity::getTicker));
        // cycleId → ticker 역매핑
        return cycleRepo.findAllById(cycleIds).stream()
                .collect(Collectors.toMap(
                        StrategyCycleEntity::getId,
                        sc -> strategyTickerMap.getOrDefault(sc.getStrategyId(), null)
                ));
    }

    // ticker 맵을 이용한 entry 변환
    private CyclePositionHistoryEntry toEntry(CyclePositionEntity e,
                                              Map<UUID, Strategy.Ticker> tickerMap) {
        return toEntry(e, tickerMap.get(e.getStrategyCycleId()));
    }

    private CyclePositionHistoryEntry toEntry(CyclePositionEntity e, Strategy.Ticker ticker) {
        return new CyclePositionHistoryEntry(
                e.getId(), ticker,
                e.getUsdDeposit(), e.getClosingPrice(), e.getAvgPrice(),
                e.getHoldings(), e.getCreatedAt()
        );
    }

    private CyclePosition toDomain(CyclePositionEntity e) {
        return new CyclePosition(
                e.getId(), e.getStrategyCycleId(),
                e.getUsdDeposit(), e.getClosingPrice(), e.getAvgPrice(),
                e.getHoldings(), e.getCreatedAt(), e.getDeletedAt()
        );
    }

    private CyclePositionEntity toEntity(CyclePosition p) {
        CyclePositionEntity e = new CyclePositionEntity();
        e.setId(p.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setStrategyCycleId(p.strategyCycleId());
        e.setUsdDeposit(p.usdDeposit());
        e.setClosingPrice(p.closingPrice());
        e.setAvgPrice(p.avgPrice());
        e.setHoldings(p.holdings());
        return e;
    }
}
