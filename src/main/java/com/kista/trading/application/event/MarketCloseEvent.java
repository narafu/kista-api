package com.kista.trading.application.event;

import com.kista.domain.model.user.User;

// 사용자별 장 마감 알림 (UserNotificationPort.notifyMarketClose) — MarketClosedEvent(관리자·휴장 알림)와는 별개 이벤트
public record MarketCloseEvent(User user) {}
