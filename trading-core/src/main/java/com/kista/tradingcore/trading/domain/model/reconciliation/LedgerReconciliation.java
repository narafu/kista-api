package com.kista.tradingcore.trading.domain.model.reconciliation;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// 전략 원장 대사 규칙 — 관리자 알림 전용, 결과를 매매 판단·원장에 쓰지 않는다
public final class LedgerReconciliation {

    private LedgerReconciliation() {}

    // 진행 중 사이클의 최신 원장 한 줄
    public record StrategyLedger(UUID strategyId, StrategyType type, StrategyTicker ticker,
                                 BigDecimal usdDeposit, BigDecimal holdings) {}

    // 관리자에게 보낼 괴리 한 줄
    public record Finding(String message) {}

    // brokerHoldings == null(MOCK)이면 보유량 대조 없이 원장 음수만 본다
    public static List<Finding> check(List<StrategyLedger> ledgers, Map<StrategyTicker, Integer> brokerHoldings) {
        List<Finding> findings = new ArrayList<>();
        // 1) 불변식 — 원장 예수금·보유량 음수
        for (StrategyLedger l : ledgers) {
            if (l.usdDeposit().signum() < 0 || l.holdings().signum() < 0) {
                findings.add(new Finding("원장 음수: strategy=" + l.strategyId() + " " + l.type() + " " + l.ticker()
                        + " usdDeposit=" + plain(l.usdDeposit()) + " holdings=" + plain(l.holdings())));
            }
        }
        if (brokerHoldings == null) return findings;

        // 2) 종목별 실보유 < 원장 합만 경보 — 실보유가 더 많은 건 KISTA 밖 보유일 수 있어 무시
        Map<StrategyTicker, List<StrategyLedger>> byTicker = ledgers.stream()
                .collect(Collectors.groupingBy(StrategyLedger::ticker, LinkedHashMap::new, Collectors.toList()));
        byTicker.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(StrategyTicker::name)))
                .forEach(e -> {
                    BigDecimal ledgerSum = e.getValue().stream().map(StrategyLedger::holdings)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    int broker = brokerHoldings.getOrDefault(e.getKey(), 0);
                    if (BigDecimal.valueOf(broker).compareTo(ledgerSum) < 0) {
                        String detail = e.getValue().stream().map(l -> l.type() + ":" + plain(l.holdings()))
                                .collect(Collectors.joining(", "));
                        findings.add(new Finding("실보유 부족: " + e.getKey() + " 브로커=" + broker
                                + " < 원장합=" + plain(ledgerSum) + " (" + detail + ")"));
                    }
                });
        return findings;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
