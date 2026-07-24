package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.SellSufficiencyPreview;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
        @Schema(description = "계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (대상 전략에 BUY 주문이 없으면 null, 근사치)")
        BuyCompetitionSummary competition,
        @Schema(description = "SELL 판매가능수량 충족 시뮬레이션 결과 (대상 전략에 SELL 주문이 없으면 null, 근사치)")
        SellSufficiencySummary sellSufficiency
) {
    public record PositionSnapshot(
            @Schema(description = "거래 종목")
            Ticker ticker,           // 거래 종목
            @Schema(description = "보유 수량")
            int holdings,            // 보유 수량
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
                    p.holdings(),
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
    public record OrderItem(
            @Schema(description = "거래 종목")
            Ticker ticker,              // 거래 종목
            @Schema(description = "주문 유형", example = "LOC")
            Order.OrderType orderType,  // 주문 유형 (LOC/MOC/LIMIT)
            @Schema(description = "매수/매도 방향", example = "BUY")
            Order.OrderDirection direction, // 매수/매도 방향
            @Schema(description = "주문 수량")
            int quantity,               // 주문 수량
            @Schema(description = "주문 가격 (LOC/MOC는 참고용)")
            BigDecimal price            // 주문 가격 (LOC/MOC는 참고용)
    ) {
        public static OrderItem from(Order o) {
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
            int quantity,
            @Schema(description = "주문 가격")
            BigDecimal price,
            @Schema(description = "주문 상태 (취소 가능 여부 판단용)", example = "PLANNED")
            Order.OrderStatus status
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

    // BUY 예산 경쟁 시뮬레이션 결과 — TradingBuyCompetitionSimulator 산출을 그대로 노출
    // 야간 배치의 캐스케이딩 거절·가격 캡은 재현하지 않는 근사치 (docs 참고)
    public record BuyCompetitionSummary(
            @Schema(description = "대상 전략 BUY가 실제 배치에서 승인될지 근사 판정 (liveBalanceUnavailable=true면 신뢰 불가)")
            boolean sufficientBudget,
            @Schema(description = "라이브 예수금 - 타 전략 당일 PLANNED BUY 합계 (liveBalanceUnavailable=true면 null)")
            BigDecimal availableDeposit,
            @Schema(description = "대상 전략 오늘자 BUY 합계")
            BigDecimal requiredForThisStrategy,
            @Schema(description = "대상 전략보다 우선순위 앞선 경쟁 전략 필요금액 합")
            BigDecimal consumedByHigherPriority,
            @Schema(description = "우선순위가 앞선 경쟁 전략 목록 (우선순위 높은 순 정렬)")
            List<CompetingStrategy> blockedByHigherPriority,
            @Schema(description = "계산 실패/skip돼 0으로 처리된 전략 id 목록")
            List<UUID> uncertainStrategyIds,
            @Schema(description = "true면 라이브 예수금 조회 자체가 실패해 경쟁 시뮬레이션을 생략함 — 이 경우 sufficientBudget/availableDeposit은 신뢰할 수 없음")
            boolean liveBalanceUnavailable
    ) {
        public record CompetingStrategy(
                @Schema(description = "경쟁 전략 ID")
                UUID strategyId,
                @Schema(description = "경쟁 전략 타입")
                Strategy.Type type,
                @Schema(description = "경쟁 전략 거래 종목")
                Ticker ticker,
                @Schema(description = "경쟁 전략 필요 매수금액")
                BigDecimal requiredBuyUsd,
                @Schema(description = "예산 배정 우선순위 (작을수록 먼저 승인)")
                int priority
        ) {
            public static CompetingStrategy from(BuyCompetitionPreview.CompetingStrategy c) {
                return new CompetingStrategy(c.strategyId(), c.type(), c.ticker(), c.requiredBuyUsd(), c.priority());
            }
        }

        public static BuyCompetitionSummary from(BuyCompetitionPreview c) {
            return new BuyCompetitionSummary(
                    c.sufficientBudget(),
                    c.availableDeposit(),
                    c.requiredForThisStrategy(),
                    c.consumedByHigherPriority(),
                    c.blockedByHigherPriority().stream().map(CompetingStrategy::from).toList(),
                    c.uncertainStrategyIds(),
                    c.liveBalanceUnavailable()
            );
        }
    }

    // SELL 판매가능수량 충족 시뮬레이션 결과 — TradingSellSufficiencySimulator 산출을 그대로 노출
    // BUY와 달리 계좌당 종목 유일성 제약상 경쟁이 없어 단일 종목 기준 판정만 존재한다 (근사치)
    public record SellSufficiencySummary(
            @Schema(description = "대상 전략 SELL이 실제 배치에서 승인될지 근사 판정 (liveQuantityUnavailable=true면 신뢰 불가)")
            boolean sufficientQuantity,
            @Schema(description = "브로커 판매가능수량 (liveQuantityUnavailable=true면 0)")
            int sellableQuantity,
            @Schema(description = "동일 계좌·종목·거래일 기준 기존 PLANNED/PLACED SELL 예약 수량 합")
            int reservedQuantity,
            @Schema(description = "대상 전략 오늘자 SELL 합계 수량")
            int requiredQuantity,
            @Schema(description = "true면 브로커 판매가능수량 조회 자체가 실패해 충족 시뮬레이션을 생략함 — 이 경우 sufficientQuantity는 신뢰할 수 없음")
            boolean liveQuantityUnavailable
    ) {
        public static SellSufficiencySummary from(SellSufficiencyPreview p) {
            return new SellSufficiencySummary(
                    p.sufficientQuantity(), p.sellableQuantity(), p.reservedQuantity(),
                    p.requiredQuantity(), p.liveQuantityUnavailable());
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
                result.competition() == null ? null : BuyCompetitionSummary.from(result.competition()),
                result.sellSufficiency() == null ? null : SellSufficiencySummary.from(result.sellSufficiency())
        );
    }
}
