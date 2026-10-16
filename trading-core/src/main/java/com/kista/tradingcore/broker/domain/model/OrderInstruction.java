package com.kista.tradingcore.broker.domain.model;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;

// 주문 접수 지시 — BrokerOrderCorrectionPort.place() 요청. trading의 Order에서 증권사가 필요로 하는 필드만 추출해 구성한다
public record OrderInstruction(
        StrategyTicker ticker,
        OrderDirection direction,
        OrderType orderType,
        Integer quantity,
        BigDecimal price,
        String clientOrderId // 증권사 멱등키(Toss만 사용, KIS·Mock 무시) — trading Order.id 문자열, null이면 미지정
) {
    // 멱등키 없는 접수 지시 — 주문 접수 경로 밖 호출부 호환
    public OrderInstruction(StrategyTicker ticker, OrderDirection direction, OrderType orderType,
                            Integer quantity, BigDecimal price) {
        this(ticker, direction, orderType, quantity, price, null);
    }
}
