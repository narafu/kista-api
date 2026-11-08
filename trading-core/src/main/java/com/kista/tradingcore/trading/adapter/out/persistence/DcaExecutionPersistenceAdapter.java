package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaExecutionFilter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
        return jpaRepository.insertIgnoringConflict(id, e.strategyId(), e.ruleKey(), e.direction().name(), e.tradeDate(), e.status().name(),
                e.skipReason() != null ? e.skipReason().name() : null, e.orderId(), e.quantity(), e.referencePrice(), detail) == 1;
    }

    @Override
    @Transactional
    public boolean markOrderedSkipped(UUID strategyId, String ruleKey, LocalDate tradeDate, UUID orderId, DcaSkipReason reason, String detail) {
        String d = detail != null && detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) : detail;
        return jpaRepository.markOrderedSkipped(strategyId, ruleKey, tradeDate, orderId, reason.name(), d) == 1;
    }

    @Override
    public Optional<DcaExecution> find(UUID strategyId, String ruleKey, LocalDate tradeDate) {
        return jpaRepository.findByStrategyIdAndRuleKeyAndTradeDate(strategyId, ruleKey, tradeDate).map(this::toDomain);
    }

    @Override
    public Map<UUID, Map<String, DcaExecution>> findLatestByStrategyIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return Map.of();
        return jpaRepository.findLatestByStrategyIds(strategyIds).stream()
                .map(this::toDomain)
                .collect(Collectors.groupingBy(DcaExecution::strategyId,
                        Collectors.toMap(DcaExecution::ruleKey, d -> d)));
    }

    @Override
    public List<DcaExecution> findPage(UUID strategyId, DcaExecutionFilter filter, int page, int size) {
        // 같은 거래일 다중 규칙 행의 페이지 경계 안정화 — id(UUIDv7) 2차 정렬
        Sort sort = Sort.by(Sort.Order.desc("tradeDate"), Sort.Order.desc("id"));
        return jpaRepository.findAll(spec(strategyId, filter), PageRequest.of(page, size, sort))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(UUID strategyId, DcaExecutionFilter filter) {
        return jpaRepository.count(spec(strategyId, filter));
    }

    // 필터 AND 결합 — null 조건은 건너뛴다
    private static Specification<DcaExecutionEntity> spec(UUID strategyId, DcaExecutionFilter f) {
        Specification<DcaExecutionEntity> spec = (root, q, cb) -> cb.equal(root.get("strategyId"), strategyId);
        if (f.from() != null) spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("tradeDate"), f.from()));
        if (f.to() != null) spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("tradeDate"), f.to()));
        if (f.direction() != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("direction"), f.direction()));
        if (f.ruleKey() != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("ruleKey"), f.ruleKey()));
        return spec;
    }

    private DcaExecution toDomain(DcaExecutionEntity e) {
        return new DcaExecution(e.getId(), e.getStrategyId(), e.getRuleKey(), e.getDirection(), e.getTradeDate(), e.getStatus(), e.getSkipReason(),
                e.getOrderId(), e.getQuantity(), e.getReferencePrice(), e.getDetail(), e.getCreatedAt());
    }
}
