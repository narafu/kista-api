package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.StrategyCyclePort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyCyclePersistenceAdapter implements StrategyCyclePort {

    private final StrategyCycleJpaRepository jpaRepository;

    @Override
    public StrategyCycle save(StrategyCycle cycle) {
        return toDomain(jpaRepository.save(toEntity(cycle)));
    }

    @Override
    public Optional<StrategyCycle> findLatestByStrategyId(UUID strategyId) {
        // @SQLRestriction 적용 — deleted_at IS NULL 중 createdAt 최신 1건
        return jpaRepository.findTop1ByStrategyIdOrderByCreatedAtDesc(strategyId)
                .map(this::toDomain);
    }

    @Override
    public void deleteByStrategyId(UUID strategyId) {
        jpaRepository.softDeleteByStrategyId(strategyId, Instant.now());
    }

    @Override
    public void deleteByAccountId(UUID accountId) {
        jpaRepository.softDeleteByAccountId(accountId, Instant.now());
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }

    private StrategyCycle toDomain(StrategyCycleEntity e) {
        return new StrategyCycle(
                e.getId(), e.getStrategyId(), e.getInitialUsdDeposit(),
                e.getCreatedAt(), e.getDeletedAt()
        );
    }

    private StrategyCycleEntity toEntity(StrategyCycle c) {
        StrategyCycleEntity e = new StrategyCycleEntity();
        e.setId(c.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setStrategyId(c.strategyId());
        e.setInitialUsdDeposit(c.initialUsdDeposit());
        return e;
    }
}
