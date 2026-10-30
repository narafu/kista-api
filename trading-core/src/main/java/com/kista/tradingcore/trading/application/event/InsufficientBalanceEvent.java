package com.kista.tradingcore.trading.application.event;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.UUID;

// 예수금 부족 알림 — userId==null이면 관리자 알림(trading-core TradingNotifyAdapter가 수신,
// holdings/usdDeposit 필수/strategyType 미사용), non-null이면 사용자 알림(UserNotificationPort.notifyInsufficientBalance(
// user,accountNickname,strategyType,ticker), strategyType 필수/holdings·usdDeposit 미사용) — 두 포트 메서드의
// 파라미터 합집합을 한 이벤트에 담고, 발행처가 쓰지 않는 쪽 필드는 sentinel(0/null)로 둔다
public record InsufficientBalanceEvent(UUID userId, UUID accountId, String accountNickname,
                                        int holdings, BigDecimal usdDeposit,
                                        StrategyTicker ticker, StrategyType strategyType,
                                        int failedCount, boolean partiallyPlaced,
                                        OrderDirection direction) { // failedCount>0: 증권사 잔고·수량 부족 거절 건수(direction 방향), 0: 원장 상한 거절 또는 관리자 알림(BUY)
    // direction 필드 도입 전에 저장된 EPR 행은 null — BUY로 본다
    public OrderDirection directionOrBuy() {
        return direction == null ? OrderDirection.BUY : direction;
    }
}
