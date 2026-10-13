package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class StrategyDcaDetailPersistenceAdapter implements StrategyDcaDetailPort {

    private final StrategyDcaVersionJpaRepository jpaRepository;

    @Override
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
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<DcaConfig> findActiveByStrategyId(UUID strategyId) {
        return jpaRepository.findActiveByStrategyId(strategyId).map(this::toDomain);
    }

    @Override
    public Map<UUID, DcaConfig> findActiveByStrategyIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return Map.of();
        // 활성 versionId 링크 → 엔티티 일괄 조회 → strategyId 키로 조립
        var links = jpaRepository.findActiveLinks(strategyIds);
        Map<UUID, StrategyDcaVersionEntity> byVersion = jpaRepository
                .findAllById(links.stream().map(StrategyDcaVersionJpaRepository.StrategyVersionLink::getStrategyVersionId).toList())
                .stream().collect(Collectors.toMap(StrategyDcaVersionEntity::getStrategyVersionId, e -> e));
        return links.stream().collect(Collectors.toMap(
                StrategyDcaVersionJpaRepository.StrategyVersionLink::getStrategyId,
                l -> toDomain(byVersion.get(l.getStrategyVersionId()))));
    }

    private DcaConfig toDomain(StrategyDcaVersionEntity e) {
        return new DcaConfig(e.getDirection(), e.getAmountUsd(), e.getOrderType(),
                e.getFrequency(), e.getDayOfWeek(), e.getDayOfMonth());
    }
}
