package com.kista.sharedkernel;

import java.util.UUID;

// 스케쥴러 인터럽트(배포·재기동) 사용자 알림 (UserNotificationPort.notifyBatchInterrupted)
// notify 리스너가 재조회 없이 바로 소비할 수 있도록 accountNickname을 직접 담는다 (Task17 fix round 2)
public record BatchInterruptedEvent(UUID userId, UUID accountId, String accountNickname) {}
