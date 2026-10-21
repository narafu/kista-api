package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
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

    private final StrategyDcaRuleJpaRepository ruleRepository;
    private final StrategyDcaConditionJpaRepository conditionRepository; // 규칙별 조건

    @Override
    @Transactional
    public DcaStrategyConfig save(UUID strategyVersionId, DcaStrategyConfig config) {
        // 버전 규칙 교체 — 즉시 삭제(조건은 CASCADE) 후 입력 순서대로 insert
        ruleRepository.deleteAllByStrategyVersionId(strategyVersionId);
        List<DcaRule> rules = config.rules();
        List<StrategyDcaRuleEntity> savedRules = ruleRepository.saveAll(IntStream.range(0, rules.size())
                .mapToObj(i -> StrategyDcaRuleEntity.of(strategyVersionId, i, rules.get(i))).toList());
        List<StrategyDcaConditionEntity> conditions = conditionRepository.saveAll(IntStream.range(0, rules.size())
                .boxed()
                .flatMap(i -> IntStream.range(0, rules.get(i).conditions().size()).mapToObj(j ->
                        StrategyDcaConditionEntity.of(savedRules.get(i).getId(), j, rules.get(i).conditions().get(j))))
                .toList());
        return toDomain(savedRules, conditions);
    }

    @Override
    public Optional<DcaStrategyConfig> findActiveByStrategyId(UUID strategyId) {
        return Optional.ofNullable(findActiveByStrategyIds(List.of(strategyId)).get(strategyId));
    }

    @Override
    public Map<UUID, DcaStrategyConfig> findActiveByStrategyIds(Collection<UUID> strategyIds) {
        if (strategyIds.isEmpty()) return Map.of();
        // 활성 versionId 링크 → 규칙·조건 일괄 조회(각 1회, N+1 방지) → strategyId 키로 조립
        var links = ruleRepository.findActiveLinks(strategyIds);
        if (links.isEmpty()) return Map.of(); // 활성 버전이 없으면 후속 IN 쿼리 생략
        List<StrategyDcaRuleEntity> rules = ruleRepository.findByStrategyVersionIdInOrderBySortOrderAsc(
                links.stream().map(StrategyDcaRuleJpaRepository.StrategyVersionLink::getStrategyVersionId).toList());
        List<StrategyDcaConditionEntity> conditions = conditionRepository.findByRuleIdInOrderBySortOrderAsc(
                rules.stream().map(StrategyDcaRuleEntity::getId).toList());
        Map<UUID, List<StrategyDcaRuleEntity>> rulesByVersion = rules.stream()
                .collect(Collectors.groupingBy(StrategyDcaRuleEntity::getStrategyVersionId));
        return links.stream().collect(Collectors.toMap(
                StrategyDcaRuleJpaRepository.StrategyVersionLink::getStrategyId,
                l -> toDomain(rulesByVersion.get(l.getStrategyVersionId()), conditions)));
    }

    // 규칙(sort_order 순) + 조건(규칙별 sort_order 순) → 설정
    private DcaStrategyConfig toDomain(List<StrategyDcaRuleEntity> rules, List<StrategyDcaConditionEntity> conditions) {
        Map<UUID, List<StrategyDcaConditionEntity>> byRule = conditions.stream()
                .collect(Collectors.groupingBy(StrategyDcaConditionEntity::getRuleId));
        return new DcaStrategyConfig(rules.stream()
                .map(r -> r.toDomain(byRule.getOrDefault(r.getId(), List.of()).stream()
                        .map(StrategyDcaConditionEntity::toDomain).toList()))
                .toList());
    }
}
