package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.StrategyCyclePort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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
    public Optional<StrategyCycle> findFirstByStrategyId(UUID strategyId) {
        // @SQLRestriction 적용 — deleted_at IS NULL 중 createdAt 가장 오래된 1건
        return jpaRepository.findTop1ByStrategyIdOrderByCreatedAtAsc(strategyId)
                .map(this::toDomain);
    }

    @Override
    public void markEnded(UUID cycleId, BigDecimal endAmount, LocalDate endDate) {
        // 사이클 종료 기록: load-set-save 패턴 (OrderPersistenceAdapter.markFilled와 동일)
        StrategyCycleEntity e = jpaRepository.findById(cycleId)
                .orElseThrow(() -> new IllegalStateException("StrategyCycle not found: " + cycleId));
        e.setEndAmount(endAmount);
        e.setEndDate(endDate);
        jpaRepository.save(e);
    }

    @Override
    public void updateStartAmount(UUID cycleId, BigDecimal startAmount) {
        // 시드 수정: load-set-save 패턴 (markEnded와 동일)
        StrategyCycleEntity e = jpaRepository.findById(cycleId)
                .orElseThrow(() -> new IllegalStateException("StrategyCycle not found: " + cycleId));
        e.setStartAmount(startAmount);
        jpaRepository.save(e);
    }

    @Override
    public List<StrategyCycle> findByStrategyIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return List.of();
        return jpaRepository.findByStrategyIdInAndDeletedAtIsNullOrderByCreatedAtAsc(strategyIds)
                .stream().map(this::toDomain).toList();
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
                e.getId(), e.getStrategyId(), e.getStrategyVersionId(),
                e.getStartAmount(), e.getEndAmount(), e.getStartDate(), e.getEndDate(),
                e.getCreatedAt(), e.getDeletedAt()
        );
    }

    private StrategyCycleEntity toEntity(StrategyCycle c) {
        StrategyCycleEntity e = new StrategyCycleEntity();
        e.setId(c.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setStrategyId(c.strategyId());
        e.setStrategyVersionId(c.strategyVersionId());
        e.setStartAmount(c.startAmount());
        e.setEndAmount(c.endAmount());
        e.setStartDate(c.startDate());
        e.setEndDate(c.endDate());
        return e;
    }
}
