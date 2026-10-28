package com.kista.tradingcore.trading.domain.model;

import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,                       // PRIVACY/skip 시 null
        List<PlannedOrder> orders,                        // NO_CYCLE_HISTORY/NO_PRIVACY_BASE skip 시 빈 리스트
        SkipReason skipReason,                           // 정상이면 null
        List<Order> todayOrders,                          // 오늘 이미 등록된 PLANNED·PLACED 주문 (없으면 빈 리스트) — DTO(NextOrdersResponse.todayOrders)와 이름 통일
        BigDecimal otherStrategiesPlannedBuyUsd,          // 계좌 내 타 전략 당일 PLANNED BUY 합계
        LedgerCheckPreview ledgerCheck,                   // 전략 원장 대비 BUY 경고 (DCA·신규 BUY 없음·skip이면 null)
        AccountCashCheckPreview accountCashCheck          // 계좌 주문가능 USD 대비 BUY 경고 — 계좌 단위·조회 시점 추정·경고 전용, 해당 없음/조회 실패면 null
) {
    public NextOrdersPreview withAccountCashCheck(AccountCashCheckPreview check) {
        return new NextOrdersPreview(tradeDate, position, orders, skipReason, todayOrders, otherStrategiesPlannedBuyUsd, ledgerCheck, check);
    }

    public enum SkipReason {
        NO_CYCLE_HISTORY,          // 사이클 이력 없음 (신규)
        NO_PRIVACY_BASE,           // PRIVACY 기준매매표 미수신
        SCHEDULED_START_NOT_REACHED, // 사이클 시작예정일 미도래
        CYCLE_ENDED,               // 최신 사이클이 종료됨 (청산 후 재등록 미완료) — 재등록/수동 개입 필요
        PRIVACY_BASE_BLOCKED       // PRIVACY 기준매매표 점검 이슈로 오늘 신규 주문 차단
    }
}
