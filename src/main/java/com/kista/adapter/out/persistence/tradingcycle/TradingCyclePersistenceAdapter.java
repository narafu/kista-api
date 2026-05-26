package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.port.out.TradingCyclePort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class TradingCyclePersistenceAdapter implements TradingCyclePort {

    private final TradingCycleJpaRepository jpaRepository;

    @Override
    public List<TradingCycle> findByAccountId(UUID accountId) {
        return jpaRepository.findAllByAccountId(accountId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<TradingCycle> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<TradingCycle> findAllActive() {
        // ACTIVE 사용자의 ACTIVE 사이클 전체 조회 (스케줄러용)
        return jpaRepository.findAllActiveCycles().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public TradingCycle save(TradingCycle cycle) {
        TradingCycleEntity entity = toEntity(cycle);
        return toDomain(jpaRepository.save(entity));
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
    public boolean existsByAccountIdAndType(UUID accountId, TradingCycle.Type type) {
        return jpaRepository.existsByAccountIdAndType(accountId, type);
    }

    private TradingCycle toDomain(TradingCycleEntity e) {
        return new TradingCycle(
                e.getId(), e.getAccountId(), e.getType(), e.getStatus(),
                e.getTicker(), e.getMultiple(), e.getInitialUsdDeposit(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }

    private TradingCycleEntity toEntity(TradingCycle c) {
        TradingCycleEntity e = new TradingCycleEntity();
        e.setId(c.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setAccountId(c.accountId());
        e.setType(c.type());
        e.setStatus(c.status());
        e.setTicker(c.ticker());
        e.setMultiple(c.multiple());
        e.setInitialUsdDeposit(c.initialUsdDeposit());
        return e;
    }
}
