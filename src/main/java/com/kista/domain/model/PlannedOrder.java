package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PlannedOrder(
        UUID id,                        // PK (null이면 신규)
        UUID accountId,                 // FK → accounts.id
        LocalDate tradeDate,            // 거래일
        String symbol,                  // 종목 코드 (예: SOXL)
        Order.OrderType orderType,      // 주문 유형 (LOC/MOC/LIMIT)
        Order.OrderDirection direction, // 매수/매도 방향
        int qty,                        // 주문 수량
        BigDecimal price,               // 주문 가격 (LOC/MOC는 참고용)
        PlannedOrderStatus status,      // 실행 상태
        String kisOrderId               // EXECUTED 이후 KIS 부여 주문번호
) {
    public enum PlannedOrderStatus {
        PENDING,   // kisOrderPort.place() 대기 중
        EXECUTED   // kisOrderPort.place() 완료
    }

    // Order → PlannedOrder: 계획 단계에서 저장 전 사용
    public static PlannedOrder from(Order order, UUID accountId) {
        return new PlannedOrder(
                null,
                accountId,
                order.tradeDate(),
                order.symbol(),
                order.orderType(),
                order.direction(),
                order.qty(),
                order.price(),
                PlannedOrderStatus.PENDING,
                null // 아직 KIS 접수 전
        );
    }

    // PlannedOrder → Order: kisOrderPort.place()에 넘길 Order 재구성
    public Order toOrder() {
        return new Order(tradeDate, symbol, orderType, direction, qty, price,
                Order.OrderStatus.PLACED, null);
    }
}
