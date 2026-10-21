package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.Market;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
}
