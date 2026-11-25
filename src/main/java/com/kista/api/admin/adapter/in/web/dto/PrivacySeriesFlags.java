package com.kista.api.admin.adapter.in.web.dto;

import com.kista.shared.contract.privacy.PrivacyTradeBaseResponse;
import com.kista.shared.kernel.Currency;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 매매표 행만으로 계산되는 차트 파생값 — kista-ui privacyChartSeries.ts의 cycleBoundary·yearlyResetFlags·cashOf 이식
// (시세가 필요한 값 — 종가·현금 비율·연초 재설정 추정 손익·누적 실현손익 — 은 UI에 남는다)
record PrivacySeriesFlags(boolean cycleBoundary, boolean yearlyReset) {

    private static final BigDecimal YEARLY_RESET_START = new BigDecimal("10000"); // 연초 재설정 시작금액(USD)

    // 행 id → 플래그. 종목별로 발표일 오름차순 이력을 훑는다(첫 행은 둘 다 false)
    static Map<UUID, PrivacySeriesFlags> of(List<PrivacyTradeBaseResponse> views) {
        Map<UUID, PrivacySeriesFlags> result = new HashMap<>();
        Map<String, List<PrivacyTradeBaseResponse>> byTicker = new HashMap<>();
        views.forEach(v -> byTicker.computeIfAbsent(v.ticker(), t -> new ArrayList<>()).add(v));
        byTicker.values().forEach(rows -> {
            List<PrivacyTradeBaseResponse> sorted = rows.stream()
                    .sorted(Comparator.comparing(PrivacyTradeBaseResponse::releaseDate)).toList();
            int lastBoundaryYear = -1; // 마지막으로 사이클 경계가 있었던 해
            PrivacyTradeBaseResponse prev = null;
            for (PrivacyTradeBaseResponse base : sorted) {
                // 시작금액이 직전 행과 다르면 경계, 그 해 첫 경계이면서 새 시작금액이 1만 달러면 연초 재설정
                boolean boundary = prev != null && prev.currentCycleStart().compareTo(base.currentCycleStart()) != 0;
                boolean reset = false;
                if (boundary) {
                    int year = base.releaseDate().getYear();
                    reset = lastBoundaryYear != year && base.currentCycleStart().compareTo(YEARLY_RESET_START) == 0;
                    lastBoundaryYear = year;
                }
                result.put(base.id(), new PrivacySeriesFlags(boundary, reset));
                prev = base;
            }
        });
        return result;
    }

    // 추정 현금 — 보유 중인데 평단가가 없으면 계산 불가(null). 실현손익은 현금 회수, 별도 입출금 없음 가정
    static @Nullable BigDecimal cash(PrivacyTradeBaseResponse v) {
        BigDecimal cost = BigDecimal.ZERO;
        if (v.holdings() > 0) {
            if (v.avgPrice() == null) return null;
            cost = v.avgPrice().multiply(BigDecimal.valueOf(v.holdings()));
        }
        return Currency.USD.round(v.currentCycleStart().add(v.currentCycleRealizedPnl()).subtract(cost));
    }
}
