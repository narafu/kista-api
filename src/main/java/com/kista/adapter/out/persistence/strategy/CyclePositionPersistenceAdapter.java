package com.kista.adapter.out.persistence.strategy;

import com.kista.common.TimeZones;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.CyclePositionInfiniteDetail;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.CyclePositionInfiniteDetailPort;
import com.kista.domain.port.out.CyclePositionPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public List<CyclePosition> findLatestByCycleId(UUID cycleId, int limit) {
        // strategy_cycle_id 기준 최신 N건 직접 조회 (리버스모드 별지점 계산용)
        return positionRepo.findTopNByStrategyCycleIdOrderByCreatedAtDesc(cycleId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to) {
        return toEntries(positionRepo.findByAccountIdAndDateRange(accountId, from, to));
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
        return toEntries(positionRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)));
    }

    @Override
    public List<CyclePositionHistoryEntry> findRecentByUser(UUID userId, int limit) {
        return toEntries(positionRepo.findRecentByUserId(userId, PageRequest.of(0, limit)));
    }

    @Override
    public List<CyclePositionHistoryEntry> findBetween(LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay(TimeZones.KST).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(TimeZones.KST).toInstant(); // to 당일 포함
        return toEntries(positionRepo.findBetweenDates(fromInstant, toInstant));
    }

    @Override
    public List<CyclePositionHistoryEntry> findBetweenByUser(UUID userId, LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay(TimeZones.KST).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(TimeZones.KST).toInstant();
        return toEntries(positionRepo.findBetweenDatesByUserId(userId, fromInstant, toInstant));
    }

    @Override
    public List<CyclePositionHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from,
                                                                      Instant cursor, int limit) {
        return toEntries(positionRepo.findByAccountIdWithCursor(accountId, from, cursor, PageRequest.of(0, limit)));
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
    public void updateCycleStartSnapshot(UUID strategyId, BigDecimal newDeposit) {
        CyclePositionEntity latest = cycleRepo.findTop1ByStrategyIdOrderByCreatedAtDesc(strategyId)
                .flatMap(sc -> positionRepo.findTopNByStrategyCycleIdOrderByCreatedAtDesc(
                        sc.getId(), PageRequest.of(0, 1)).stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: " + strategyId));

        latest.setUsdDeposit(newDeposit);
        positionRepo.save(latest);
    }

    @Override
    public void softDeleteTodayByStrategyId(UUID strategyId, LocalDate kstDate) {
        Instant dayStart = kstDate.atStartOfDay(TimeZones.KST).toInstant();
        Instant dayEnd = kstDate.plusDays(1).atStartOfDay(TimeZones.KST).toInstant();
        positionRepo.softDeleteByStrategyIdAndDate(strategyId, dayStart, dayEnd, Instant.now());
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
        // strategy_cycle 1회 조회 후 재사용 (이전: findAllById 3회 호출)
        List<StrategyCycleEntity> cycles = cycleRepo.findAllById(cycleIds);
        // strategy_id → ticker
        Set<UUID> strategyIds = cycles.stream()
                .map(StrategyCycleEntity::getStrategyId)
                .collect(Collectors.toSet());
        Map<UUID, Strategy.Ticker> strategyTickerMap = strategyRepo.findAllById(strategyIds).stream()
                .collect(Collectors.toMap(StrategyEntity::getId, StrategyEntity::getTicker));
        // cycleId → ticker 역매핑
        return cycles.stream()
                .collect(Collectors.toMap(
                        StrategyCycleEntity::getId,
                        sc -> strategyTickerMap.getOrDefault(sc.getStrategyId(), null)
                ));
    }

    // 포지션 목록 → 히스토리 entry 목록 (ticker 맵 1회 구성 후 일괄 변환)
    private List<CyclePositionHistoryEntry> toEntries(List<CyclePositionEntity> entities) {
        Map<UUID, Strategy.Ticker> tickerMap = buildTickerMapFromPositions(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
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

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CyclePositionInfiniteDetailPersistenceAdapter implements CyclePositionInfiniteDetailPort {

    private final CyclePositionInfiniteJpaRepository jpaRepository;

    @Override
    public Optional<CyclePositionInfiniteDetail> findByCyclePositionId(UUID cyclePositionId) {
        return jpaRepository.findById(cyclePositionId).map(this::toDomain);
    }

    @Override
    public List<CyclePositionInfiniteDetail> findLatestByCycleId(UUID cycleId, int limit) {
        return jpaRepository.findTopNByStrategyCycleIdOrderByCreatedAtDesc(cycleId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public CyclePositionInfiniteDetail save(CyclePositionInfiniteDetail detail) {
        return toDomain(jpaRepository.save(toEntity(detail)));
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        jpaRepository.deleteByStrategyId(strategyId);
    }

    private CyclePositionInfiniteDetail toDomain(CyclePositionInfiniteEntity entity) {
        return new CyclePositionInfiniteDetail(entity.getCyclePositionId(), entity.isReverseMode());
    }

    private CyclePositionInfiniteEntity toEntity(CyclePositionInfiniteDetail detail) {
        CyclePositionInfiniteEntity entity = detail.cyclePositionId() != null
                ? jpaRepository.findById(detail.cyclePositionId()).orElseGet(CyclePositionInfiniteEntity::new)
                : new CyclePositionInfiniteEntity();
        entity.setCyclePositionId(detail.cyclePositionId());
        entity.setReverseMode(detail.isReverseMode());
        return entity;
    }
}
