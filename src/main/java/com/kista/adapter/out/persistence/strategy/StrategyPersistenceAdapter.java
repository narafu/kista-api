package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.admin.AdminCycleStrategySummary;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.StrategyPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyPersistenceAdapter implements StrategyPort {

    private final StrategyJpaRepository jpaRepository;

    @Override
    public List<Strategy> findByAccountId(UUID accountId) {
        return jpaRepository.findAllByAccountId(accountId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Strategy> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Strategy> findAllActive() {
        // ACTIVE 사용자의 ACTIVE 전략 전체 조회 (스케쥴러용)
        return jpaRepository.findAllActiveStrategies().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Strategy save(Strategy strategy) {
        return toDomain(jpaRepository.save(toEntity(strategy)));
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void deleteByAccountId(UUID accountId) {
        jpaRepository.softDeleteByAccountId(accountId, Instant.now());
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }

    @Override
    public Map<UUID, List<Strategy>> findByAccountIds(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) return Map.of();
        return jpaRepository.findAllByAccountIdIn(accountIds).stream()
                .map(this::toDomain)
                .collect(Collectors.groupingBy(Strategy::accountId));
    }

    @Override
    public Map<UUID, AdminCycleStrategySummary> findSummariesByCycleIds(Collection<UUID> cycleIds) {
        if (cycleIds.isEmpty()) return Map.of();
        return jpaRepository.findStrategySummariesByCycleIds(cycleIds).stream()
                .collect(Collectors.toMap(
                        StrategyJpaRepository.CycleStrategySummaryProjection::getCycleId,
                        r -> new AdminCycleStrategySummary(
                                r.getStrategyId(),
                                Strategy.Type.valueOf(r.getStrategyType())
                        )
                ));
    }

    @Override
    public boolean existsByAccountIdAndTicker(UUID accountId, Strategy.Ticker ticker) {
        return jpaRepository.existsByAccountIdAndTicker(accountId, ticker);
    }

    private Strategy toDomain(StrategyEntity e) {
        return new Strategy(
                e.getId(), e.getAccountId(), e.getType(), e.getStatus(),
                e.getTicker(), e.getCycleSeedType()
        );
    }

    private StrategyEntity toEntity(Strategy s) {
        StrategyEntity e = PersistenceSupport.findOrCreate(s.id(), jpaRepository, StrategyEntity::new);
        e.setId(s.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setAccountId(s.accountId());
        e.setType(s.type());
        e.setStatus(s.status());
        e.setTicker(s.ticker());
        e.setCycleSeedType(s.cycleSeedType() != null ? s.cycleSeedType() : Strategy.CycleSeedType.NONE);
        return e;
    }
}
