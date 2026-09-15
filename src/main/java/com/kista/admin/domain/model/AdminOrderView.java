package com.kista.admin.domain.model;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// trading.domain.model.Order의 admin own-type read model — TradingQueryHttpAdapter가
// trading-core 내부 API(/api/internal/trading/orders 등) 응답을 이 타입으로 역직렬화한다.
// 서버가 Order 전체(15필드)를 그대로 반환하므로 필드를 하나라도 빠뜨리면 Jackson의
// FAIL_ON_UNKNOWN_PROPERTIES 설정에 암묵 의존하게 된다 — orderLeg를 포함해 전체 필드를 복제한다.
public record AdminOrderView(
        UUID id,
        UUID accountId,
        UUID strategyCycleId,
        LocalDate tradeDate,
        StrategyTicker ticker,
        OrderType orderType,
        OrderTiming timing,
        OrderDirection direction,
        String orderLeg,
        Integer quantity,
        BigDecimal price,
        OrderStatus status,
        String externalOrderId,
        Integer filledQuantity,
        BigDecimal filledPrice
) {}
