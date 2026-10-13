package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class DcaExecutionPersistenceAdapter implements DcaExecutionPort {

    private static final int DETAIL_MAX = 500; // detail 컬럼 길이

    private final DcaExecutionJpaRepository jpaRepository;

    @Override
    @Transactional
    public boolean insertIfAbsent(DcaExecution e) {
        // native insert는 @UuidGenerator가 동작하지 않아 Hibernate의 v7 생성기를 직접 사용
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        String detail = e.detail() != null && e.detail().length() > DETAIL_MAX ? e.detail().substring(0, DETAIL_MAX) : e.detail();
        return jpaRepository.insertIgnoringConflict(id, e.strategyId(), e.tradeDate(), e.status().name(),
                e.skipReason() != null ? e.skipReason().name() : null, e.orderId(), e.quantity(), e.referencePrice(), detail) == 1;
    }

    @Override
    public Optional<DcaExecution> find(UUID strategyId, LocalDate tradeDate) {
        return jpaRepository.findByStrategyIdAndTradeDate(strategyId, tradeDate).map(this::toDomain);
    }

    @Override
    public Map<UUID, DcaExecution> findLatestByStrategyIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return Map.of();
        return jpaRepository.findLatestByStrategyIds(strategyIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(DcaExecution::strategyId, d -> d));
    }

    @Override
    public List<DcaExecution> findPage(UUID strategyId, int page, int size) {
        return jpaRepository.findByStrategyIdOrderByTradeDateDesc(strategyId, PageRequest.of(page, size))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(UUID strategyId) {
        return jpaRepository.countByStrategyId(strategyId);
    }

    private DcaExecution toDomain(DcaExecutionEntity e) {
        return new DcaExecution(e.getId(), e.getStrategyId(), e.getTradeDate(), e.getStatus(), e.getSkipReason(),
                e.getOrderId(), e.getQuantity(), e.getReferencePrice(), e.getDetail(), e.getCreatedAt());
    }
}
