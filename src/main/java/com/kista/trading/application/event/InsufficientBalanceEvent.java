package com.kista.trading.application.event;

import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.AccountBalance;

import java.util.UUID;

// 예수금 부족 알림 — userId==null이면 관리자 알림(NotifyPort.notifyInsufficientBalance(account,b,ticker),
// b 필수/strategyType 미사용), non-null이면 사용자 알림(UserNotificationPort.notifyInsufficientBalance(
// user,account,strategyType,ticker), strategyType 필수/b 미사용) — 두 포트 메서드의 파라미터 합집합을
// 한 이벤트에 담고, 발행처가 쓰지 않는 쪽 필드는 null로 둔다
public record InsufficientBalanceEvent(UUID userId, UUID accountId, AccountBalance b,
                                        Strategy.Ticker ticker, Strategy.Type strategyType) {}
