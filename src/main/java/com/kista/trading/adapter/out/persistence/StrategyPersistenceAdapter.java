package com.kista.trading.adapter.out.persistence;

import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySummary;
import com.kista.trading.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@Component
@RequiredArgsConstructor
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
    public Map<UUID, StrategySummary> findSummariesByCycleIds(Collection<UUID> cycleIds) {
        if (cycleIds.isEmpty()) return Map.of();
        return jpaRepository.findStrategySummariesByCycleIds(cycleIds).stream()
                .collect(Collectors.toMap(
                        StrategyJpaRepository.CycleStrategySummaryProjection::getCycleId,
                        r -> new StrategySummary(
                                r.getStrategyId(),
                                StrategyType.valueOf(r.getStrategyType())
                        )
                ));
    }

    @Override
    public boolean existsByAccountIdAndTicker(UUID accountId, StrategyTicker ticker) {
        return jpaRepository.existsByAccountIdAndTicker(accountId, ticker);
    }

    @Override
    public Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return Map.of();
        return jpaRepository.findAllById(strategyIds).stream()
                .collect(Collectors.toMap(StrategyEntity::getId, StrategyEntity::getTicker));
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
        e.setCycleSeedType(s.cycleSeedType() != null ? s.cycleSeedType() : StrategyCycleSeedType.NONE);
        return e;
    }
}
