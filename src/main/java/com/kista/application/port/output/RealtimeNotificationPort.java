package com.kista.application.port.output;

import com.kista.trading.domain.model.TradeEvent;

import java.util.UUID;
import com.kista.sharedkernel.UserStatus;

public interface RealtimeNotificationPort {
    // 특정 사용자의 상태 변경을 SSE로 실시간 알림
    void notifyStatusChange(UUID userId, UserStatus status);

    // 특정 사용자에게 매매 이벤트를 SSE로 실시간 알림
    void notifyTrade(UUID userId, TradeEvent event);
}
