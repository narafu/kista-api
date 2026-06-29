package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyInfiniteDetail;
import com.kista.domain.port.out.StrategyInfiniteDetailPort;
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
    public Map<UUID, Strategy.Type> findTypesByCycleIds(Collection<UUID> cycleIds) {
        if (cycleIds.isEmpty()) return Map.of();
        return jpaRepository.findStrategyTypesByCycleIds(cycleIds).stream()
                .collect(Collectors.toMap(
                        StrategyJpaRepository.CycleStrategyType::getCycleId,
                        r -> Strategy.Type.valueOf(r.getStrategyType())
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
        StrategyEntity e = s.id() != null
                ? jpaRepository.findById(s.id()).orElseGet(StrategyEntity::new)
                : new StrategyEntity();
        e.setId(s.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setAccountId(s.accountId());
        e.setType(s.type());
        e.setStatus(s.status());
        e.setTicker(s.ticker());
        e.setCycleSeedType(s.cycleSeedType() != null ? s.cycleSeedType() : Strategy.CycleSeedType.NONE);
        return e;
    }
}

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyInfiniteDetailPersistenceAdapter implements StrategyInfiniteDetailPort {

    private final StrategyInfiniteJpaRepository jpaRepository;

    @Override
    public Optional<StrategyInfiniteDetail> findByStrategyVersionId(UUID strategyVersionId) {
        return jpaRepository.findById(strategyVersionId).map(this::toDomain);
    }

    @Override
    public Optional<StrategyInfiniteDetail> findActiveByStrategyId(UUID strategyId) {
        return jpaRepository.findActiveByStrategyId(strategyId).map(this::toDomain);
    }

    @Override
    public StrategyInfiniteDetail save(StrategyInfiniteDetail detail) {
        return toDomain(jpaRepository.save(toEntity(detail)));
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        jpaRepository.softDeleteByStrategyId(strategyId, Instant.now());
    }

    private StrategyInfiniteDetail toDomain(StrategyInfiniteEntity entity) {
        return new StrategyInfiniteDetail(entity.getStrategyVersionId(), entity.getDivisionCount());
    }

    private StrategyInfiniteEntity toEntity(StrategyInfiniteDetail detail) {
        StrategyInfiniteEntity entity = detail.strategyVersionId() != null
                ? jpaRepository.findById(detail.strategyVersionId()).orElseGet(StrategyInfiniteEntity::new)
                : new StrategyInfiniteEntity();
        entity.setStrategyVersionId(detail.strategyVersionId());
        entity.setDivisionCount(detail.divisionCount());
        return entity;
    }
}
