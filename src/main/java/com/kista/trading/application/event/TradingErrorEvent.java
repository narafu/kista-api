package com.kista.trading.application.event;

import com.kista.domain.model.user.User;

// 관리자/사용자 매매 오류 알림 — user==null이면 관리자 전용(NotifyPort.notifyError(Exception)),
// non-null이면 사용자 알림(UserNotificationPort.notifyError(User,Exception)). 동일 오류를 관리자+사용자
// 양쪽에 알려야 하는 발행처는 이 이벤트를 두 번(null, 실제 user) 각각 발행한다
public record TradingErrorEvent(User user, Exception e) {}
