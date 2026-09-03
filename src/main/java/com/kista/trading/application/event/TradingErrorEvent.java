package com.kista.trading.application.event;

import java.util.UUID;

// 관리자/사용자 매매 오류 알림 — userId==null이면 관리자 전용(NotifyPort.notifyError(Exception)),
// non-null이면 사용자 알림(UserNotificationPort.notifyError(User,Exception)). 동일 오류를 관리자+사용자
// 양쪽에 알려야 하는 발행처는 이 이벤트를 두 번(null, 실제 userId) 각각 발행한다.
// message는 원본 Exception.getMessage() — 소비처 전부 메시지 텍스트만 사용해 정보 손실 없음
public record TradingErrorEvent(UUID userId, String message) {}
