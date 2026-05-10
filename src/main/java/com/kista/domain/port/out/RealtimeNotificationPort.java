package com.kista.domain.port.out;

import com.kista.domain.model.UserStatus;

import java.util.UUID;

public interface RealtimeNotificationPort {
    // 특정 사용자의 상태 변경을 SSE로 실시간 알림
    void notifyStatusChange(UUID userId, UserStatus status);
}
