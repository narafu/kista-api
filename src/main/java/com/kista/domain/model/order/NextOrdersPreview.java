package com.kista.domain.model.order;

import com.kista.domain.model.strategy.InfinitePosition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,                       // PRIVACY/skip 시 null
        List<Order> orders,                              // NO_CYCLE_HISTORY/NO_PRIVACY_BASE skip 시 빈 리스트
        SkipReason skipReason,                           // 정상이면 null
        List<Order> todayOrders,                          // 오늘 이미 등록된 PLANNED·PLACED 주문 (없으면 빈 리스트) — DTO(NextOrdersResponse.todayOrders)와 이름 통일
        BigDecimal otherStrategiesPlannedBuyUsd,          // 계좌 내 타 전략 당일 PLANNED BUY 합계
        BuyCompetitionPreview competition                // 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (BUY 없으면 null)
) {
    public enum SkipReason {
        NO_CYCLE_HISTORY,   // 사이클 이력 없음 (신규)
        NO_PRIVACY_BASE     // PRIVACY 기준매매표 미수신
    }
}
