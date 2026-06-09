package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.order.NextOrdersPreview;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NextOrdersResponse(
        LocalDate tradeDate,
        PositionSnapshot position,                    // PRIVACY/skip 시 null
        List<OrderItem> orders,
        NextOrdersPreview.SkipReason skipReason    // 정상이면 null
) {
    public record PositionSnapshot(
            Ticker ticker,           // 거래 종목
            int holdings,            // 보유 수량
            BigDecimal averagePrice, // 평균 매입가 (0회차: 전일종가)
            BigDecimal usdDeposit,   // 통합주문가능금액
            BigDecimal priceOffsetRate, // 가격 보정률 (전반: >0, 후반: ≤0)
            double currentRound,     // 현재 회차 (소수점 허용)
            BigDecimal unitAmount,   // 1회 매수 단위금액 (총자산 / 20)
            BigDecimal referencePrice,  // 기준가 (LOC 주문 가격 기준)
            BigDecimal targetPrice,  // 목표가 (지정가 매도 기준)
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
            Ticker ticker,              // 거래 종목
            Order.OrderType orderType,  // 주문 유형 (LOC/MOC/LIMIT)
            Order.OrderDirection direction, // 매수/매도 방향
            int quantity,               // 주문 수량
            BigDecimal price            // 주문 가격 (LOC/MOC는 참고용)
    ) {
        public static OrderItem from(Order o) {
            return new OrderItem(o.ticker(), o.orderType(), o.direction(), o.quantity(), o.price());
        }
    }

    public static NextOrdersResponse from(NextOrdersPreview result) {
        return new NextOrdersResponse(
                result.tradeDate(),
                result.position() == null ? null : PositionSnapshot.from(result.position()),
                result.orders().stream().map(OrderItem::from).toList(),
                result.skipReason()
        );
    }
}
