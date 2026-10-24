package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.trading.domain.model.LedgerCheckPreview;
import com.kista.tradingcore.trading.domain.model.NextOrdersPreview;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.StrategyTicker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.kista.shared.kernel.StrategyType;

public record NextOrdersResponse(
        @Schema(description = "다음 매매 예정일 (KST 기준)")
        LocalDate tradeDate,
        @Schema(description = "INFINITE 전략 포지션 스냅샷 (PRIVACY 전략 또는 skip 시 null)")
        PositionSnapshot position,                    // PRIVACY/skip 시 null
        @Schema(description = "생성 예정 주문 목록")
        List<OrderItem> orders,
        @Schema(description = "주문 생성 skip 사유 (정상이면 null)", example = "NO_PRIVACY_BASE")
        NextOrdersPreview.SkipReason skipReason,      // 정상이면 null
        @Schema(description = "오늘 이미 등록된 PLANNED·PLACED 주문 목록")
        List<TodayOrderItem> todayOrders,             // 오늘 이미 등록된 PLANNED·PLACED 주문
        @Schema(description = "계좌 내 타 전략의 당일 PLANNED BUY 합계 (USD)")
        BigDecimal otherStrategiesPlannedBuyUsd,       // 계좌 내 타 전략 당일 PLANNED BUY 합계
        @Schema(description = "전략 원장 대비 BUY 경고 (DCA·신규 BUY 없음·skip이면 null)")
        LedgerCheckSummary ledgerCheck
) {
    public record PositionSnapshot(
            @Schema(description = "거래 종목")
            StrategyTicker ticker,           // 거래 종목
            @Schema(description = "보유 수량")
            BigDecimal holdings,         // 보유 수량
            @Schema(description = "평균 매입가 (0회차: 전일종가)")
            BigDecimal averagePrice, // 평균 매입가 (0회차: 전일종가)
            @Schema(description = "통합주문가능금액")
            BigDecimal usdDeposit,   // 통합주문가능금액
            @Schema(description = "가격 보정률 (전반: 양수, 후반: 0 이하)")
            BigDecimal priceOffsetRate, // 가격 보정률 (전반: >0, 후반: ≤0)
            @Schema(description = "현재 회차 (소수점 허용)", example = "3.5")
            double currentRound,     // 현재 회차 (소수점 허용)
            @Schema(description = "1회 매수 단위금액 (총자산 / 분할 수)")
            BigDecimal unitAmount,   // 1회 매수 단위금액 (총자산 / 20)
            @Schema(description = "기준가 (LOC 주문 가격 기준)")
            BigDecimal referencePrice,  // 기준가 (LOC 주문 가격 기준)
            @Schema(description = "목표가 (지정가 매도 기준)")
            BigDecimal targetPrice,  // 목표가 (지정가 매도 기준)
            @Schema(description = "총 자산 (예수금 + 매입금액)")
            BigDecimal totalAssets   // 총 자산 (예수금 + 매입금액)
    ) {
        public static PositionSnapshot from(InfinitePosition p) {
            return new PositionSnapshot(
                    p.ticker(),
                    BigDecimal.valueOf(p.holdings()),
                    p.averagePrice(),
                    p.usdDeposit(),
                    p.priceOffsetRate(),
                    p.currentRound(),
                    p.unitAmount(),
                    p.referencePrice(),
                    p.targetPrice(),
                    p.totalAssets()
            );
        }
    }

    // tradeDate·status·orderId는 preview에서 의미 없으므로 제외
    @Schema(name = "NextOrderItem")
    public record OrderItem(
            @Schema(description = "거래 종목")
            StrategyTicker ticker,              // 거래 종목
            @Schema(description = "주문 유형", example = "LOC")
            OrderType orderType,  // 주문 유형 (LOC/MOC/LIMIT)
            @Schema(description = "매수/매도 방향", example = "BUY")
            OrderDirection direction, // 매수/매도 방향
            @Schema(description = "주문 수량")
            int quantity,               // 주문 수량
            @Schema(description = "주문 가격 (LOC/MOC는 참고용)")
            BigDecimal price            // 주문 가격 (LOC/MOC는 참고용)
    ) {
        public static OrderItem from(PlannedOrder o) {
            return new OrderItem(o.ticker(), o.orderType(), o.direction(), o.quantity(), o.price());
        }
    }

    // 오늘 등록된 주문 항목 (PLANNED·PLACED 모두 포함 — status로 취소 가능 여부 판단)
    public record TodayOrderItem(
            @Schema(description = "주문 고유 ID")
            UUID id,
            @Schema(description = "거래 종목", example = "TQQQ")
            String ticker,
            @Schema(description = "매수/매도 방향", example = "BUY")
            String direction,
            @Schema(description = "주문 유형", example = "LOC")
            String orderType,
            @Schema(description = "주문 수량")
            BigDecimal quantity,
            @Schema(description = "주문 가격")
            BigDecimal price,
            @Schema(description = "주문 상태 (취소 가능 여부 판단용)", example = "PLANNED")
            OrderStatus status
    ) {
        public static TodayOrderItem from(Order o) {
            return new TodayOrderItem(
                    o.id(),
                    o.ticker().name(),
                    o.direction().name(),
                    o.orderType().name(),
                    o.quantity(),
                    o.price(),
                    o.status()
            );
        }
    }

    // 전략 원장(cycle_position.usd_deposit) 대비 BUY 판정 — 배치와 같은 StrategyLedgerBudget 산출
    public record LedgerCheckSummary(
            @Schema(description = "전략 원장 잔여 (같은 날 저장된 BUY 차감 후, USD)")
            BigDecimal remainingLedger,
            @Schema(description = "오늘 신규 BUY 원본 합계 (USD)")
            BigDecimal requiredBuyUsd,
            @Schema(description = "false면 원장 부족으로 BUY 전체가 거절된다")
            boolean sufficient,
            @Schema(description = "true면 원장에 맞춰 BUY가 축소돼 저장된다")
            boolean trimmed
    ) {
        public static LedgerCheckSummary from(LedgerCheckPreview p) {
            return new LedgerCheckSummary(p.remainingLedger(), p.requiredBuyUsd(), p.sufficient(), p.trimmed());
        }
    }

    public static NextOrdersResponse from(NextOrdersPreview result) {
        return new NextOrdersResponse(
                result.tradeDate(),
                result.position() == null ? null : PositionSnapshot.from(result.position()),
                result.orders().stream().map(OrderItem::from).toList(),
                result.skipReason(),
                result.todayOrders().stream().map(TodayOrderItem::from).toList(),
                result.otherStrategiesPlannedBuyUsd(),
                result.ledgerCheck() == null ? null : LedgerCheckSummary.from(result.ledgerCheck())
        );
    }
}

