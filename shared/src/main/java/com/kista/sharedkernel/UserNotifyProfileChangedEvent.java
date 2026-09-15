package com.kista.sharedkernel;

import java.util.Map;
import java.util.UUID;

// trading-core 소유 user_notify_profile 캐시 동기화용 — user의 알림·잔고검증 설정 또는 활성 상태가 바뀔 때 발행.
// user.domain.model이 아닌 sharedkernel에 두는 이유: trading-core가 이 타입을 import하는 순간
// trading-core → root(:api) 역방향 컴파일 의존이 되살아난다.
// DB 분리(4단계) 전까지는 같은 DB 위 Modulith EPR(@TransactionalEventListener)로 전달된다.
public record UserNotifyProfileChangedEvent(
        UUID userId,                                      // 사용자 식별자
        Map<NotificationType, Boolean> notificationPrefs, // UserSettings.notificationPrefs와 동일 shape
        boolean balanceCheckEnabled,                      // 잔고검증 활성 여부
        boolean active,                                   // UserStatus.ACTIVE 여부 — findAllActive() 브로드캐스트 대상 판정용
        String telegramBotToken,                          // AES 평문(User.telegramBotToken()은 persistence 경계에서 이미 복호화됨) — trading-core가 자체 DB 저장 시 암호화
        String chatId                                      // 텔레그램 chat ID — 비밀값 아니라 평문 그대로 저장
) {
}
