package com.kista.domain.strategy;

import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderStatus.PLACED;
import static com.kista.domain.model.order.Order.OrderType.LIMIT;

@Component
public class CorrectionStrategy {

    // 장 마감 후 unitAmount 잔여 매수 여력을 종가로 추가 매수
    // quantity = floor((unitAmount - 당일 BUY 체결합) / 종가)
    public List<Order> correct(InfinitePosition position, BigDecimal closingPrice,
                               List<Execution> executions, LocalDate tradeDate) {
        // 당일 BUY 체결 금액 합계
        BigDecimal filledBuyAmount = executions.stream()
                .filter(e -> e.direction() == BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 잔여 매수 여력
        BigDecimal remaining = position.unitAmount().subtract(filledBuyAmount);
        if (remaining.signum() <= 0) return List.of();

        // 종가로 매수 가능 수량
        int quantity = remaining.divide(closingPrice, 0, RoundingMode.FLOOR).intValue();
        if (quantity < 1) return List.of();

        // LIMIT BUY — KIS는 시간대 기반으로 애프터마켓 자동 라우팅 (ORD_DVSN=00)
        return List.of(new Order(null, null, tradeDate, position.ticker(),
                LIMIT, BUY, quantity, closingPrice, PLACED, null));
    }
}
