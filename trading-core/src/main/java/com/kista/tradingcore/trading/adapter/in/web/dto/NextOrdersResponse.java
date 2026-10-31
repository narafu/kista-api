package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.tradingcore.trading.domain.model.AccountCashCheckPreview;
import com.kista.tradingcore.trading.domain.model.AccountHoldingsCheckPreview;
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
        LedgerCheckSummary ledgerCheck,
        @Schema(description = "계좌 주문가능 USD 대비 오늘 BUY 경고 — 계좌 단위(계좌 내 모든 전략 동일 값), 조회 시점 추정, 경고 전용. MOCK·코인·필요 BUY 없음·증권사 조회 실패면 null")
        AccountCashCheckSummary accountCashCheck,
        @Schema(description = "계좌 보유(판매 가능) 수량 대비 오늘 SELL 경고 — (계좌, 종목) 단위(같은 종목 전략 동일 값), 조회 시점 추정, 경고 전용. MOCK·코인·필요 SELL 없음·증권사 조회 실패면 null")
        AccountHoldingsCheckSummary accountHoldingsCheck
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
            BigDecimal price,           // 주문 가격 (LOC/MOC는 참고용)
            @Schema(description = "접수 시점 (AT_OPEN=개장 선접수, AT_CLOSE=마감 배치, IMMEDIATE=즉시)", example = "AT_CLOSE")
            OrderTiming timing  // 접수 시점
    ) {
        public static OrderItem from(PlannedOrder o) {
            return new OrderItem(o.ticker(), o.orderType(), o.direction(), o.quantity(), o.price(), o.timing());
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
            OrderStatus status,
            @Schema(description = "접수 시점 (AT_OPEN=개장 선접수, AT_CLOSE=마감 배치, IMMEDIATE=즉시)", example = "AT_CLOSE")
            OrderTiming timing  // 접수 시점
    ) {
        public static TodayOrderItem from(Order o) {
            return new TodayOrderItem(
                    o.id(),
                    o.ticker().name(),
                    o.direction().name(),
                    o.orderType().name(),
                    o.quantity(),
                    o.price(),
                    o.status(),
                    o.timing()
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

    // 계좌 실제 주문가능 USD 대비 BUY 경고 — 계좌 단위
    public record AccountCashCheckSummary(
            @Schema(description = "증권사 USD 주문가능금액 (조회 시점, 접수 완료 주문은 증권사가 차감)")
            BigDecimal availableUsd,
            @Schema(description = "계좌 전체 오늘 BUY 필요 금액 (PLANNED·SUBMITTING BUY + 전략별 원장 반영 후 신규 BUY, USD) — SUBMITTING은 증권사 차감 여부가 불확실해 보수적으로 포함")
            BigDecimal requiredBuyUsd,
            @Schema(description = "requiredBuyUsd 중 INFINITE 보정 주문(LOC 1주) 지정가 합계 — 보정 제외 금액은 requiredBuyUsd − correctionBuyUsd. 접수 시에는 지정가 전체가 주문가능금액에서 묶이지만 실제 체결은 전략별 단위금액 안에서 끝난다. 보정 주문이 없으면 0")
            BigDecimal correctionBuyUsd,
            @Schema(description = "false면 배치 접수 시 증권사가 BUY를 거절할 수 있다")
            boolean sufficient
    ) {
        public static AccountCashCheckSummary from(AccountCashCheckPreview p) {
            return new AccountCashCheckSummary(p.availableUsd(), p.requiredBuyUsd(), p.correctionBuyUsd(), p.sufficient());
        }
    }

    // 계좌 실제 보유(판매 가능) 수량 대비 SELL 경고 — (계좌, 종목) 단위
    public record AccountHoldingsCheckSummary(
            @Schema(description = "증권사 판매 가능 수량 (조회 시점, 미체결 매도 차감 전 보유 수량)")
            BigDecimal availableQuantity,
            @Schema(description = "종목 전체 오늘 SELL 필요 수량 (PLANNED·SUBMITTING·PLACED SELL + 신규 SELL) — 판매 가능 수량이 미체결 매도를 차감하기 전 보유 수량이라 접수 완료(PLACED) SELL도 포함한다. 장중 일부 체결된 PLACED SELL은 주문 수량 전체로 세므로 보수적으로(부족 쪽으로) 표시될 수 있다")
            BigDecimal requiredSellQuantity,
            @Schema(description = "false면 배치 접수 시 증권사가 SELL을 거절할 수 있다")
            boolean sufficient
    ) {
        public static AccountHoldingsCheckSummary from(AccountHoldingsCheckPreview p) {
            return new AccountHoldingsCheckSummary(p.availableQuantity(), p.requiredSellQuantity(), p.sufficient());
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
                result.ledgerCheck() == null ? null : LedgerCheckSummary.from(result.ledgerCheck()),
                result.accountCashCheck() == null ? null : AccountCashCheckSummary.from(result.accountCashCheck()),
                result.accountHoldingsCheck() == null ? null : AccountHoldingsCheckSummary.from(result.accountHoldingsCheck())
        );
    }
}

