package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.trading.domain.model.Strategy;
import java.util.Arrays;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CyclePositionPersistenceAdapter implements CyclePositionPort {

    // 사용자 합산·계좌 이력 화면은 USD 표시 전제 — USD 견적 아님(코인 KRW·USDT 등)은 제외 (통계 합산 경로의 USD 포함식 필터와 동일)
    private static final List<String> NON_USD_BROKERS = Arrays.stream(Broker.values())
            .filter(b -> b.getCurrency() != Currency.USD).map(Enum::name).toList();

    private final CyclePositionJpaRepository positionRepo;
    private final StrategyCycleJpaRepository cycleRepo;     // strategy_cycle 조인: 현재 사이클 조회
    private final StrategyPort strategyPort;            // ticker 조회: strategy_cycle → strategy (trading own-type 포트 경유)

    @Override
    public CyclePosition save(CyclePosition position) {
        return toDomain(positionRepo.save(toEntity(position)));
    }

    @Override
    public List<CyclePosition> findLatestByStrategyId(UUID strategyId, int limit) {
        // 현재 사이클(latest strategy_cycle) 의 최신 포지션 N건 반환
        return cycleRepo.findTop1ByStrategyIdOrderByCreatedAtDesc(strategyId)
                .map(sc -> positionRepo.findByStrategyCycleIdOrderByCreatedAtDesc(
                        sc.getId(), PageRequest.of(0, limit)))
                .orElse(List.of())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<CyclePosition> findLatestByCycleId(UUID cycleId, int limit) {
        // strategy_cycle_id 기준 최신 N건 직접 조회 (리버스모드 별지점 계산용)
        return positionRepo.findByStrategyCycleIdOrderByCreatedAtDesc(cycleId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<CyclePosition> findFirstOne(UUID cycleId) {
        return positionRepo.findTop1ByStrategyCycleIdOrderByCreatedAtAsc(cycleId)
                .map(this::toDomain);
    }

    @Override
    public Map<UUID, CyclePosition> findFirstByCycleIds(Collection<UUID> cycleIds) {
        if (cycleIds.isEmpty()) return Map.of();
        return positionRepo.findFirstByStrategyCycleIdIn(cycleIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(CyclePosition::strategyCycleId, p -> p));
    }

    @Override
    public Map<UUID, CyclePosition> findLatestByCycleIds(Collection<UUID> cycleIds) {
        if (cycleIds.isEmpty()) return Map.of();
        return positionRepo.findLatestByStrategyCycleIdIn(cycleIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(CyclePosition::strategyCycleId, p -> p));
    }

    @Override
    public List<CyclePositionHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to) {
        return toEntries(positionRepo.findByAccountIdAndDateRange(accountId, from, to));
    }

    @Override
    public List<CyclePositionHistoryEntry> findByStrategyIdAndDateRange(UUID strategyId, Instant from, Instant to) {
        // ticker·type은 strategy에서 한 번만 조회
        Strategy strategy = strategyPort.findById(strategyId).orElse(null);
        return positionRepo.findByStrategyIdAndDateRange(strategyId, from, to).stream()
                .map(e -> toEntry(e, strategy))
                .toList();
    }

    @Override
    public List<CyclePositionHistoryEntry> findRecentByUser(UUID userId, int limit) {
        return toEntries(positionRepo.findRecentByUserId(userId, NON_USD_BROKERS, PageRequest.of(0, limit)));
    }

    @Override
    public List<CyclePosition> findByUserAndRange(UUID userId, Instant from, Instant to) {
        return positionRepo.findByUserIdAndRange(userId, from, to)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<CyclePosition> findByCycleIdsAndRange(Set<UUID> cycleIds, Instant from, Instant to) {
        if (cycleIds.isEmpty()) return List.of(); // 불필요한 쿼리 방지
        return positionRepo.findByCycleIdsAndRange(cycleIds, from, to)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<CyclePosition> findByStrategyAndRange(UUID strategyId, Instant from, Instant to) {
        return positionRepo.findByStrategyIdAndDateRange(strategyId, from, to)
                .stream().map(this::toDomain).toList().reversed();
    }

    @Override
    public List<CyclePositionHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from,
                                                                      Instant cursor, int limit) {
        return toEntries(positionRepo.findByAccountIdWithCursor(accountId, NON_USD_BROKERS, from, cursor, PageRequest.of(0, limit)));
    }

    @Override
    public List<CyclePositionHistoryEntry> findByStrategyIdWithCursor(UUID strategyId, Instant from,
                                                                       Instant cursor, int limit) {
        Strategy strategy = strategyPort.findById(strategyId).orElse(null);
        return positionRepo.findByStrategyIdWithCursor(strategyId, from, cursor, PageRequest.of(0, limit))
                .stream().map(e -> toEntry(e, strategy)).toList();
    }

    @Override
    public void updateCycleStartSnapshot(UUID strategyId, BigDecimal newDeposit) {
        CyclePositionEntity latest = cycleRepo.findTop1ByStrategyIdOrderByCreatedAtDesc(strategyId)
                .flatMap(sc -> positionRepo.findByStrategyCycleIdOrderByCreatedAtDesc(
                        sc.getId(), PageRequest.of(0, 1)).stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("포지션 이력 없음: " + strategyId));

        latest.setUsdDeposit(newDeposit);
        positionRepo.save(latest);
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
    private Map<UUID, Strategy> buildStrategyMapFromPositions(List<CyclePositionEntity> entities) {
        Set<UUID> cycleIds = entities.stream()
                .map(CyclePositionEntity::getStrategyCycleId)
                .collect(Collectors.toSet());
        if (cycleIds.isEmpty()) return Map.of();
        // strategy_cycle 1회 조회 후 재사용 (이전: findAllById 3회 호출)
        List<StrategyCycleEntity> cycles = cycleRepo.findAllById(cycleIds);
        // strategy_id → strategy
        Set<UUID> strategyIds = cycles.stream()
                .map(StrategyCycleEntity::getStrategyId)
                .collect(Collectors.toSet());
        Map<UUID, Strategy> strategyMap = strategyPort.findAllByIds(strategyIds);
        // cycleId → strategy 역매핑 (삭제된 전략은 키 없음 — toMap은 null 값을 허용하지 않는다)
        Map<UUID, Strategy> result = new java.util.HashMap<>();
        cycles.forEach(sc -> {
            Strategy s = strategyMap.get(sc.getStrategyId());
            if (s != null) result.put(sc.getId(), s);
        });
        return result;
    }

    // 포지션 목록 → 히스토리 entry 목록 (전략 맵 1회 구성 후 일괄 변환)
    private List<CyclePositionHistoryEntry> toEntries(List<CyclePositionEntity> entities) {
        Map<UUID, Strategy> strategyMap = buildStrategyMapFromPositions(entities);
        return entities.stream().map(e -> toEntry(e, strategyMap.get(e.getStrategyCycleId()))).toList();
    }

    // strategy가 없으면(삭제 등) ticker·type null
    private CyclePositionHistoryEntry toEntry(CyclePositionEntity e, Strategy strategy) {
        return new CyclePositionHistoryEntry(
                e.getId(), strategy != null ? strategy.ticker() : null, strategy != null ? strategy.type() : null,
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
        e.setId(p.id()); // null이면 @UuidGenerator가 UUID v7 생성
        e.setStrategyCycleId(p.strategyCycleId());
        e.setUsdDeposit(p.usdDeposit());
        e.setClosingPrice(p.closingPrice());
        e.setAvgPrice(p.avgPrice());
        e.setHoldings(p.holdings());
        return e;
    }
}
