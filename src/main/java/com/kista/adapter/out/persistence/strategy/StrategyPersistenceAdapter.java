package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.StrategyRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyPersistenceAdapter implements StrategyRepository {

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
        // ACTIVE 사용자의 ACTIVE 전략 전체 조회 (스케줄러용)
        return jpaRepository.findAllActiveStrategies().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Strategy save(Strategy strategy) {
        StrategyEntity entity = toEntity(strategy);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public boolean existsByAccountIdAndType(UUID accountId, Strategy.StrategyType type) {
        return jpaRepository.existsByAccountIdAndType(accountId, type);
    }

    private Strategy toDomain(StrategyEntity e) {
        return new Strategy(
                e.getId(), e.getAccountId(), e.getType(), e.getStatus(),
                e.getTicker(), e.getMultiple(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }

    private StrategyEntity toEntity(Strategy s) {
        StrategyEntity e = new StrategyEntity();
        e.setId(s.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setAccountId(s.accountId());
        e.setType(s.type());
        e.setStatus(s.status());
        e.setTicker(s.ticker());
        e.setMultiple(s.multiple());
        return e;
    }
}
