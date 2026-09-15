package com.kista.sharedkernel;

import java.math.BigDecimal;

// broker.domain.model.Execution의 notify 전용 narrowing — TradingReportReadyEvent.executions가
// 이벤트 payload를 sharedkernel+JDK로만 구성하기 위해 필요한 최소 필드만 담는다
public record TradeLegSummary(
        OrderDirection direction,  // 매수/매도 방향
        StrategyTicker ticker,     // 종목 코드
        int quantity,              // 체결 수량
        BigDecimal price,          // 체결 단가 (USD)
        BigDecimal amountUsd       // 체결 금액 (USD)
) {
}
