package com.kista.domain.port.out;

import com.kista.domain.model.TradeEvent;
import com.kista.domain.model.UserStatus;

import java.util.UUID;

public interface RealtimeNotificationPort {
    // 특정 사용자의 상태 변경을 SSE로 실시간 알림
    void notifyStatusChange(UUID userId, UserStatus status);

    // 특정 사용자에게 매매 이벤트를 SSE로 실시간 알림
    void notifyTrade(UUID userId, TradeEvent event);
}
