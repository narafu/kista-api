package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.tradingcore.trading.domain.model.CyclePosition;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 투자금 추가·인출 이력으로 cycle_position 시계열·원금·장부가를 복원하는 순수 헬퍼
final class CapitalLedgerTimeline {

    private CapitalLedgerTimeline() {}

    // 이벤트가 걸린 행을 갱신 전 원본으로 되돌리고, 이벤트 시각마다 가상 스냅샷을 추가해 createdAt 오름차순으로 반환
    static List<CyclePosition> restore(List<CyclePosition> positions, List<CycleCapitalEvent> events) {
        Map<UUID, List<CycleCapitalEvent>> byRow = events.stream()
                .collect(Collectors.groupingBy(CycleCapitalEvent::cyclePositionId));
        List<CyclePosition> result = new ArrayList<>();
        for (CyclePosition row : positions) {
            List<CycleCapitalEvent> rowEvents = byRow.getOrDefault(row.id(), List.of()).stream()
                    .sorted(Comparator.comparing(CycleCapitalEvent::occurredAt)).toList();
            if (rowEvents.isEmpty()) {
                result.add(row);
                continue;
            }
            // 원본 = 현재값 − 모든 이벤트 증감
            result.add(withValues(row, row.createdAt(),
                    row.usdDeposit().subtract(sum(rowEvents, 0, CycleCapitalEvent::depositDelta)),
                    row.holdings().subtract(sum(rowEvents, 0, CycleCapitalEvent::holdingsDelta))));
            // e_k 직후 스냅샷 = 현재값 − 이후 이벤트 증감 (avgPrice는 R 현재값 사용)
            // ponytail: 가상 스냅샷 avgPrice는 R 현재값 — 종가 없을 때 평단 대체 평가에만 쓰여 오차 허용
            for (int k = 0; k < rowEvents.size(); k++) {
                result.add(withValues(row, rowEvents.get(k).occurredAt(),
                        row.usdDeposit().subtract(sum(rowEvents, k + 1, CycleCapitalEvent::depositDelta)),
                        row.holdings().subtract(sum(rowEvents, k + 1, CycleCapitalEvent::holdingsDelta))));
            }
        }
        result.sort(Comparator.comparing(CyclePosition::createdAt)); // 안정 정렬
        return result;
    }

    // KST 일자가 date 이후인 이벤트의 원금 증감 합
    static BigDecimal principalDeltaAfter(List<CycleCapitalEvent> cycleEvents, LocalDate date) {
        return cycleEvents.stream()
                .filter(e -> e.occurredAt().atZone(TimeZones.KST).toLocalDate().isAfter(date))
                .map(CycleCapitalEvent::principalDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal principalDeltaTotal(List<CycleCapitalEvent> cycleEvents) {
        return sum(cycleEvents, 0, CycleCapitalEvent::principalDelta);
    }

    // 장부가 = 예수금 + 보유수량 × 평단(없으면 0)
    static BigDecimal bookValue(CyclePosition p) {
        BigDecimal avg = p.avgPrice() != null ? p.avgPrice() : BigDecimal.ZERO;
        return p.usdDeposit().add(p.holdings().multiply(avg));
    }

    // 장부가 실현손익 = 마지막 장부가 − (첫 행 장부가 − 첫 행에 걸린 원금 증감) − 전체 원금 증감
    static BigDecimal bookRealized(CyclePosition first, CyclePosition last, List<CycleCapitalEvent> cycleEvents) {
        BigDecimal firstRowPrincipal = cycleEvents.stream()
                .filter(e -> e.cyclePositionId().equals(first.id()))
                .map(CycleCapitalEvent::principalDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return bookValue(last)
                .subtract(bookValue(first).subtract(firstRowPrincipal))
                .subtract(principalDeltaTotal(cycleEvents));
    }

    private static BigDecimal sum(List<CycleCapitalEvent> events, int from, Function<CycleCapitalEvent, BigDecimal> f) {
        return events.subList(from, events.size()).stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static CyclePosition withValues(CyclePosition r, Instant createdAt, BigDecimal deposit, BigDecimal holdings) {
        return new CyclePosition(r.id(), r.strategyCycleId(), deposit, r.closingPrice(), r.avgPrice(), holdings, createdAt, r.deletedAt());
    }
}
