package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyDcaDetailPersistenceAdapter implements StrategyDcaDetailPort {

    private final StrategyDcaVersionJpaRepository jpaRepository;
    private final StrategyDcaConditionJpaRepository conditionRepository; // 버전별 조건

    @Override
    @Transactional
    public DcaConfig save(UUID strategyVersionId, DcaConfig config) {
        // find-or-create upsert — strategy_version_id PK 기준
        StrategyDcaVersionEntity entity = PersistenceSupport.findOrCreate(
                strategyVersionId, jpaRepository, StrategyDcaVersionEntity::new);
        entity.setStrategyVersionId(strategyVersionId);
        entity.setDirection(config.direction());
        entity.setAmountUsd(config.amountUsd());
        entity.setOrderType(config.orderType());
        entity.setFrequency(config.frequency());
        entity.setDayOfWeek(config.dayOfWeek());
        entity.setDayOfMonth(config.dayOfMonth());
        StrategyDcaVersionEntity saved = jpaRepository.save(entity);
        // 조건 교체 — 즉시 삭제 후 입력 순서대로 insert
        conditionRepository.deleteAllByStrategyVersionId(strategyVersionId);
        List<StrategyDcaConditionEntity> conditions = conditionRepository.saveAll(
                IntStream.range(0, config.conditions().size())
                        .mapToObj(i -> StrategyDcaConditionEntity.of(strategyVersionId, i, config.conditions().get(i)))
                        .toList());
        return toDomain(saved, conditions);
    }

    @Override
    public Optional<DcaConfig> findActiveByStrategyId(UUID strategyId) {
        return jpaRepository.findActiveByStrategyId(strategyId).map(e -> toDomain(e,
                conditionRepository.findByStrategyVersionIdInOrderBySortOrderAsc(List.of(e.getStrategyVersionId()))));
    }

    @Override
    public Map<UUID, DcaConfig> findActiveByStrategyIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return Map.of();
        // 활성 versionId 링크 → 엔티티 일괄 조회 → strategyId 키로 조립
        var links = jpaRepository.findActiveLinks(strategyIds);
        Map<UUID, StrategyDcaVersionEntity> byVersion = jpaRepository
                .findAllById(links.stream().map(StrategyDcaVersionJpaRepository.StrategyVersionLink::getStrategyVersionId).toList())
                .stream().collect(Collectors.toMap(StrategyDcaVersionEntity::getStrategyVersionId, e -> e));
        // 조건 1회 일괄 조회 후 버전별 그룹핑 (N+1 방지)
        Map<UUID, List<StrategyDcaConditionEntity>> conditionsByVersion = conditionRepository
                .findByStrategyVersionIdInOrderBySortOrderAsc(byVersion.keySet())
                .stream().collect(Collectors.groupingBy(StrategyDcaConditionEntity::getStrategyVersionId));
        return links.stream().collect(Collectors.toMap(
                StrategyDcaVersionJpaRepository.StrategyVersionLink::getStrategyId,
                l -> toDomain(byVersion.get(l.getStrategyVersionId()),
                        conditionsByVersion.getOrDefault(l.getStrategyVersionId(), List.of()))));
    }

    private DcaConfig toDomain(StrategyDcaVersionEntity e, List<StrategyDcaConditionEntity> conditions) {
        return new DcaConfig(e.getDirection(), e.getAmountUsd(), e.getOrderType(),
                e.getFrequency(), e.getDayOfWeek(), e.getDayOfMonth(),
                conditions.stream().map(StrategyDcaConditionEntity::toDomain).toList());
    }
}
