package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.Market;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// DCA 전략 설정 — 규칙 1~10개
public record DcaStrategyConfig(List<DcaRule> rules) {

    public static final int MAX_RULES = 10; // 전략당 규칙 상한

    public DcaStrategyConfig {
        if (rules == null || rules.isEmpty() || rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("DCA 규칙은 1~" + MAX_RULES + "개여야 합니다.");
        }
        rules = List.copyOf(rules);
        Set<String> keys = new HashSet<>();
        for (DcaRule r : rules) {
            if (r.ruleKey() != null && !keys.add(r.ruleKey())) throw new IllegalArgumentException("규칙 키가 중복되었습니다.");
        }
    }

    // 규칙 중 하나라도 해당 지표 조건을 쓰는지
    public boolean hasIndicator(DcaIndicator indicator) {
        return rules.stream().anyMatch(r -> r.hasIndicator(indicator));
    }

    public void requireCompatible(Market accountMarket) {
        rules.forEach(r -> r.requireCompatible(accountMarket));
    }

    // 저장 전 ruleKey 확정 — null은 신규 규칙(UUID 발급), non-null은 existingKeys(현재 활성 버전)에 있어야 한다
    public DcaStrategyConfig withAssignedKeys(Set<String> existingKeys) {
        return new DcaStrategyConfig(rules.stream().map(r -> {
            if (r.ruleKey() == null) return r.withRuleKey(UUID.randomUUID().toString());
            if (!existingKeys.contains(r.ruleKey())) throw new IllegalArgumentException("존재하지 않는 규칙입니다");
            return r;
        }).toList());
    }

    public Set<String> ruleKeys() {
        return rules.stream().map(DcaRule::ruleKey).collect(Collectors.toSet());
    }

    // 규칙 키로 조회 — 편집으로 삭제된 규칙이면 empty
    public Optional<DcaRule> rule(String ruleKey) {
        return rules.stream().filter(r -> Objects.equals(r.ruleKey(), ruleKey)).findFirst();
    }

    // 규칙 순번(1부터, 입력 순서) — 알림 표기용, 없으면 null
    public Integer ruleNumber(String ruleKey) {
        return IntStream.range(0, rules.size()).filter(i -> Objects.equals(rules.get(i).ruleKey(), ruleKey))
                .mapToObj(i -> i + 1).findFirst().orElse(null);
    }
}
