package com.kista.contract.trading;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 주문 1건 — trading-core 내부 API(/api/internal/trading/orders 등) 응답
public record OrderResponse(
        UUID id,                    // PK
        UUID accountId,             // FK → accounts.id
        UUID strategyCycleId,       // FK → strategy_cycle.id
        LocalDate tradeDate,        // 거래일 (KST)
        StrategyTicker ticker,      // 거래 종목
        OrderType orderType,        // 주문 유형 (LOC/MOC/LIMIT)
        OrderTiming timing,         // 접수 시점
        OrderDirection direction,   // 매수/매도 방향
        String orderLeg,            // 전략 주문 다리 식별자
        Integer quantity,           // 주문 수량 (nullable)
        BigDecimal price,           // 주문 가격
        OrderStatus status,         // 주문 상태
        String externalOrderId,     // 증권사 부여 주문 번호
        Integer filledQuantity,     // 체결 수량 (null=미확인)
        BigDecimal filledPrice      // 체결 가중평균가 (null=미체결)
) {}
