package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.kernel.NotificationType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// 프로필 복제본 upsert 입력 — shared.kernel 이벤트(UserNotifyProfileChangedEvent)를 포트 시그니처에서 분리한 trading 소유 스냅샷
public record TradingUserProfileChange(
        UUID userId,
        Map<NotificationType, Boolean> notificationPrefs, // 알림 유형별 활성 여부
        boolean active,                                   // 사용자 ACTIVE 여부 — findAllActive() 브로드캐스트 대상 판정용
        String telegramBotToken,                          // 평문(엔티티 컨버터가 암호화), null 가능
        String chatId,                                    // 텔레그램 chat ID, null 가능
        NotificationChannel notificationChannel,          // 알림 수단 — null이면 저장된 채널을 유지/미복제로 둔다(구버전 발행분)
        Instant occurredAt                                // 스냅샷 시각(버전 가드) — null이면 구버전 발행분(무조건 적용)
) {
}
