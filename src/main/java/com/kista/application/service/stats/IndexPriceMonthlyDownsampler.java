package com.kista.application.service.stats;

import com.kista.domain.model.stats.IndexPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 일별 종가 → 월별 마지막 거래일 종가 다운샘플링 — 통계 계산 규칙이지 DB row 변환이 아니므로
// 영속성 어댑터가 아닌 이 서비스 티어에 둔다.
final class IndexPriceMonthlyDownsampler {
    private IndexPriceMonthlyDownsampler() {}

    static Map<LocalDate, BigDecimal> toMonthlyLastClose(List<IndexPrice> dailyPrices) {
        Map<LocalDate, IndexPrice> latestPerMonth = new TreeMap<>();
        for (IndexPrice price : dailyPrices) {
            LocalDate monthKey = price.tradeDate().withDayOfMonth(1);
            latestPerMonth.merge(monthKey, price,
                    (a, b) -> b.tradeDate().isAfter(a.tradeDate()) ? b : a);
        }
        return latestPerMonth.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().close(),
                        (l, r) -> r, TreeMap::new));
    }
}
