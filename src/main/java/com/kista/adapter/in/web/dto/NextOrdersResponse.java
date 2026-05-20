package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NextOrdersResponse(
        LocalDate tradeDate,
        PositionSnapshot position,
        List<OrderItem> orders
) {
    public record PositionSnapshot(
            Ticker ticker,           // 거래 종목
            int quantity,            // 보유 수량
            BigDecimal averagePrice, // 평균 매입가
            BigDecimal usdDeposit,   // 통합주문가능금액
            BigDecimal currentPrice, // 현재가 (장마감 시 마지막 체결가)
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
                    p.quantity(),
                    p.averagePrice(),
                    p.usdDeposit(),
                    p.currentPrice(),
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
            int qty,                    // 주문 수량
            BigDecimal price            // 주문 가격 (LOC/MOC는 참고용)
    ) {
        public static OrderItem from(Order o) {
            return new OrderItem(o.ticker(), o.orderType(), o.direction(), o.qty(), o.price());
        }
    }

    public static NextOrdersResponse from(
            LocalDate tradeDate, InfinitePosition position, List<Order> orders) {
        return new NextOrdersResponse(
                tradeDate,
                PositionSnapshot.from(position),
                orders.stream().map(OrderItem::from).toList()
        );
    }
}
