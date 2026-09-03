package com.kista.trading.application.event;

import java.util.UUID;

// 스케쥴러 인터럽트(배포·재기동) 사용자 알림 (UserNotificationPort.notifyBatchInterrupted)
public record BatchInterruptedEvent(UUID userId, UUID accountId) {}
