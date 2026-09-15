package com.kista.sharedkernel;

import java.math.BigDecimal;
import java.util.UUID;

// 예수금 부족 알림 — userId==null이면 관리자 알림(NotifyPort.notifyInsufficientBalance(holdings,usdDeposit,ticker),
// holdings/usdDeposit 필수/strategyType 미사용), non-null이면 사용자 알림(UserNotificationPort.notifyInsufficientBalance(
// user,accountNickname,strategyType,ticker), strategyType 필수/holdings·usdDeposit 미사용) — 두 포트 메서드의
// 파라미터 합집합을 한 이벤트에 담고, 발행처가 쓰지 않는 쪽 필드는 sentinel(0/null)로 둔다
public record InsufficientBalanceEvent(UUID userId, UUID accountId, String accountNickname,
                                        int holdings, BigDecimal usdDeposit,
                                        StrategyTicker ticker, StrategyType strategyType) {}
